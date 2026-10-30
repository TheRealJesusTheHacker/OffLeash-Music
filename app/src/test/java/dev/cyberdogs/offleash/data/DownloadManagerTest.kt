package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.model.StreamInfo
import dev.cyberdogs.offleash.core.model.Track
import dev.cyberdogs.offleash.core.playback.QualityPreference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The download engine (Lane F), driven synchronously with a fake
 * bounded-range fetcher and a fake resolver: exact byte assembly,
 * the bounded windows actually requested, the quality handed to the
 * resolver, failure naming + partial discard, size probing, the
 * discard-don't-resume policy, and cancellation.
 *
 * What this cannot prove at JVM level: the real OkHttp fetcher's wire
 * behaviour, WorkManager scheduling, and on-device playback of a
 * downloaded file — those are CI/device proofs, not faked here.
 */
class DownloadManagerTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private fun track(videoId: String = "vid1"): Track = Track(
        videoId = videoId,
        title = "Title",
        artists = listOf("Artist"),
        artistBrowseId = null,
        album = null,
        albumBrowseId = null,
        durationSeconds = 200,
        thumbnailUrl = null,
    )

    /** Deterministic fake audio: byte i is (i % 251). */
    private fun content(total: Int): ByteArray = ByteArray(total) { (it % 251).toByte() }

    private class FakeFetcher(
        private val bytes: ByteArray,
        private val failOnCall: Int = -1,
        private val probeResult: Long? = null,
    ) : ChunkFetcher {
        val windows = mutableListOf<LongRange>()
        var probeCalls = 0

        override fun fetchRange(url: String, window: LongRange): ByteArray {
            windows.add(window)
            if (windows.size == failOnCall) {
                throw DownloadFailed("the stream server answered HTTP 500")
            }
            return bytes.copyOfRange(window.first.toInt(), window.last.toInt() + 1)
        }

        override fun probeTotalBytes(url: String): Long? {
            probeCalls++
            return probeResult
        }
    }

    private class Rig(
        val manager: DownloadManager,
        val store: DownloadStore,
        val downloadsDir: File,
        val fetcher: FakeFetcher,
        val resolvedQualities: MutableList<QualityPreference>,
    )

    private fun rig(
        total: Int = 2500,
        chunkBytes: Long = 1000L,
        contentLength: Long? = total.toLong(),
        fetcher: FakeFetcher = FakeFetcher(content(total), probeResult = total.toLong()),
        quality: QualityPreference = QualityPreference.BALANCED,
    ): Rig {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val downloadsDir = File(root, "downloads")
        val qualities = mutableListOf<QualityPreference>()
        val manager = DownloadManager(
            store = store,
            downloadsDir = downloadsDir,
            resolveStream = { _, q ->
                qualities.add(q)
                StreamInfo(
                    url = "https://example.invalid/stream",
                    mimeType = "audio/mp4",
                    bitrate = 128_000,
                    contentLength = contentLength,
                    approxDurationMs = 200_000L,
                )
            },
            fetcher = fetcher,
            qualityProvider = { quality },
            chunkBytes = chunkBytes,
            nowMs = { 42L },
        )
        return Rig(manager, store, downloadsDir, fetcher, qualities)
    }

    @Test
    fun successAssemblesExactBytesThroughBoundedWindows() {
        val r = rig()
        r.store.enqueueIfAbsent(track(), nowMs = 1L)
        val result = r.manager.runDownload(track())

        assertEquals(DownloadState.DONE, result?.state)
        assertEquals(2500L, result?.downloadedBytes)
        assertEquals(2500L, result?.totalBytes)
        assertEquals("audio/mp4", result?.mimeType)
        assertNull(result?.failureReason)

        // Exactly the planner's bounded windows, in order.
        assertEquals(
            listOf(0L..999L, 1000L..1999L, 2000L..2499L),
            r.fetcher.windows,
        )
        // The file on disk is the fake audio, byte for byte, and no
        // partial remains.
        val file = File(r.downloadsDir, downloadFileName("vid1"))
        assertTrue(file.isFile)
        assertArrayEquals(content(2500), file.readBytes())
        assertFalse(File(r.downloadsDir, partialFileName(downloadFileName("vid1"))).exists())
        // And the manager will now hand it to playback.
        assertEquals(file, r.manager.downloadedPlayback("vid1")?.file)
        assertEquals(2500L, r.manager.storageSummary().totalBytes)
        assertEquals(1, r.manager.storageSummary().downloadedCount)
    }

    @Test
    fun resolverIsHandedTheDownloadQuality() {
        val r = rig(quality = QualityPreference.LOW)
        r.store.enqueueIfAbsent(track(), nowMs = 1L)
        r.manager.runDownload(track())
        assertEquals(listOf(QualityPreference.LOW), r.resolvedQualities)
    }

    @Test
    fun failureNamesItselfAndDiscardsThePartial() {
        val failing = FakeFetcher(content(2500), failOnCall = 2)
        val r = rig(fetcher = failing)
        r.store.enqueueIfAbsent(track(), nowMs = 1L)
        val result = r.manager.runDownload(track())

        assertEquals(DownloadState.FAILED, result?.state)
        assertTrue(result?.failureReason?.contains("DownloadFailed") == true)
        assertTrue(result?.failureReason?.contains("HTTP 500") == true)
        // Discard policy: 0 kept bytes, no final file, no partial.
        assertEquals(0L, result?.downloadedBytes)
        assertFalse(File(r.downloadsDir, downloadFileName("vid1")).exists())
        assertFalse(File(r.downloadsDir, partialFileName(downloadFileName("vid1"))).exists())
        assertNull(r.manager.downloadedPlayback("vid1"))
    }

    @Test
    fun resolverRefusalAlsoFailsByName() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val manager = DownloadManager(
            store = store,
            downloadsDir = File(root, "downloads"),
            resolveStream = { _, _ -> throw DownloadFailed("YouTube won't play this track: region locked") },
            fetcher = FakeFetcher(ByteArray(0)),
            nowMs = { 42L },
        )
        store.enqueueIfAbsent(track(), nowMs = 1L)
        val result = manager.runDownload(track())
        assertEquals(DownloadState.FAILED, result?.state)
        assertTrue(result?.failureReason?.contains("region locked") == true)
    }

    @Test
    fun unknownSizeIsProbedWithABoundedRequest() {
        val fetcher = FakeFetcher(content(1200), probeResult = 1200L)
        val r = rig(total = 1200, contentLength = null, fetcher = fetcher)
        r.store.enqueueIfAbsent(track(), nowMs = 1L)
        val result = r.manager.runDownload(track())
        assertEquals(DownloadState.DONE, result?.state)
        assertEquals(1200L, result?.totalBytes)
        assertEquals(1, fetcher.probeCalls)
    }

    @Test
    fun unknownSizeWithNoProbeAnswerFailsNamed() {
        val fetcher = FakeFetcher(content(1200), probeResult = null)
        val r = rig(total = 1200, contentLength = null, fetcher = fetcher)
        r.store.enqueueIfAbsent(track(), nowMs = 1L)
        val result = r.manager.runDownload(track())
        assertEquals(DownloadState.FAILED, result?.state)
        assertTrue(result?.failureReason?.contains("did not say how big") == true)
    }

    @Test
    fun stalePartialIsDiscardedNeverResumed() {
        val r = rig()
        r.downloadsDir.mkdirs()
        // Junk partial from some interrupted earlier attempt.
        File(r.downloadsDir, partialFileName(downloadFileName("vid1")))
            .writeBytes(ByteArray(9999) { 0x7F })
        r.store.enqueueIfAbsent(track(), nowMs = 1L)
        val result = r.manager.runDownload(track())
        assertEquals(DownloadState.DONE, result?.state)
        // The fetch started at byte 0 (no resume) and the finished
        // file is exactly the real content, not the junk.
        assertEquals(0L, r.fetcher.windows.first().first)
        assertArrayEquals(
            content(2500),
            File(r.downloadsDir, downloadFileName("vid1")).readBytes(),
        )
    }

    @Test
    fun retryAfterFailureSucceedsFromScratch() {
        val failing = FakeFetcher(content(2500), failOnCall = 1)
        val r = rig(fetcher = failing)
        r.store.enqueueIfAbsent(track(), nowMs = 1L)
        assertEquals(DownloadState.FAILED, r.manager.runDownload(track())?.state)

        val good = rigFrom(r, FakeFetcher(content(2500)))
        val result = good.runDownload(track())
        assertEquals(DownloadState.DONE, result?.state)
        assertNull(result?.failureReason)
    }

    /** A second manager over the same store/dir — [runDownload] re-entry is the retry path. */
    private fun rigFrom(r: Rig, fetcher: FakeFetcher): DownloadManager = DownloadManager(
        store = r.store,
        downloadsDir = r.downloadsDir,
        resolveStream = { _, _ ->
            StreamInfo(
                url = "https://example.invalid/stream",
                mimeType = "audio/mp4",
                bitrate = 128_000,
                contentLength = 2500L,
                approxDurationMs = 200_000L,
            )
        },
        fetcher = fetcher,
        chunkBytes = 1000L,
        nowMs = { 43L },
    )

    @Test
    fun cancellationStopsAndDiscards() {
        val r = rig()
        r.store.enqueueIfAbsent(track(), nowMs = 1L)
        val result = r.manager.runDownload(track()) { false }
        assertEquals(DownloadState.FAILED, result?.state)
        assertTrue(result?.failureReason?.contains("cancelled") == true)
        assertTrue(r.fetcher.windows.isEmpty())
        assertFalse(File(r.downloadsDir, downloadFileName("vid1")).exists())
    }

    @Test
    fun deleteRemovesRecordAndFile() {
        val r = rig()
        r.store.enqueueIfAbsent(track(), nowMs = 1L)
        r.manager.runDownload(track())
        assertEquals(DownloadState.DONE, r.store.recordFor("vid1")?.state)
        r.manager.delete("vid1")
        assertNull(r.store.recordFor("vid1"))
        assertFalse(File(r.downloadsDir, downloadFileName("vid1")).exists())
        assertEquals(0L, r.manager.storageSummary().totalBytes)
    }

    @Test
    fun interruptedDownloadIsRequeuedAndItsPartialDiscardedOnManagerStart() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val downloadsDir = File(root, "downloads")
        store.enqueueIfAbsent(track(), nowMs = 1L)
        store.transition("vid1", DownloadState.DOWNLOADING, downloadedBytes = 7L, nowMs = 2L)
        downloadsDir.mkdirs()
        val part = File(downloadsDir, partialFileName(downloadFileName("vid1")))
        part.writeBytes(ByteArray(7) { 0x7F })

        // A scope cancelled before construction: the init-time
        // reconciliation runs synchronously, but no job can proceed.
        val deadScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Job().apply { cancel() },
        )
        DownloadManager(
            store = store,
            downloadsDir = downloadsDir,
            resolveStream = { _, _ -> throw DownloadFailed("must not resolve in this test") },
            fetcher = FakeFetcher(ByteArray(0)),
            scope = deadScope,
            nowMs = { 9L },
        )
        val record = store.recordFor("vid1")
        assertEquals(DownloadState.QUEUED, record?.state)
        assertEquals(0L, record?.downloadedBytes)
        assertFalse(part.exists())
    }

    // -- Sweep: integrity at the seams ------------------------------------

    /** Short first chunk, long second — the totals add up, the bytes do not. */
    private class CompensatingLiarFetcher(private val bytes: ByteArray) : ChunkFetcher {
        var calls = 0

        override fun fetchRange(url: String, window: LongRange): ByteArray {
            calls++
            val slice = bytes.copyOfRange(window.first.toInt(), window.last.toInt() + 1)
            return when (calls) {
                1 -> slice.copyOf(slice.size - 100)
                2 -> slice + ByteArray(100)
                else -> slice
            }
        }

        override fun probeTotalBytes(url: String): Long? = bytes.size.toLong()
    }

    @Test
    fun aChunkOfTheWrongSizeFailsEvenWhenTheTotalWouldAddUp() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val downloadsDir = File(root, "downloads")
        val manager = DownloadManager(
            store = store,
            downloadsDir = downloadsDir,
            resolveStream = { _, _ ->
                StreamInfo(
                    url = "https://example.invalid/stream",
                    mimeType = "audio/mp4",
                    bitrate = 128_000,
                    contentLength = 2500L,
                    approxDurationMs = 200_000L,
                )
            },
            fetcher = CompensatingLiarFetcher(content(2500)),
            scope = deadScope(),
            chunkBytes = 1000L,
            nowMs = { 42L },
        )
        store.enqueueIfAbsent(track(), nowMs = 1L)
        val result = manager.runDownload(track())
        // The manager verifies every window itself; a fetcher that
        // returns shifted bytes must never produce a DONE file.
        assertEquals(DownloadState.FAILED, result?.state)
        assertTrue(result?.failureReason?.contains("bytes for a") == true)
        assertFalse(File(downloadsDir, downloadFileName("vid1")).exists())
    }

    @Test
    fun midDownloadFailureDiscardsAndRetryReResolvesFromScratch() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val downloadsDir = File(root, "downloads")
        var resolves = 0
        val resolve: (Track, QualityPreference) -> StreamInfo = { _, _ ->
            resolves++
            StreamInfo(
                url = "https://example.invalid/stream",
                mimeType = "audio/mp4",
                bitrate = 128_000,
                contentLength = 2500L,
                approxDurationMs = 200_000L,
            )
        }
        store.enqueueIfAbsent(track(), nowMs = 1L)
        val failingManager = DownloadManager(
            store = store,
            downloadsDir = downloadsDir,
            resolveStream = resolve,
            fetcher = FakeFetcher(content(2500), failOnCall = 2),
            // Dead scope: the QUEUED record must not start a racing
            // job at construction — the test drives runDownload.
            scope = deadScope(),
            chunkBytes = 1000L,
            nowMs = { 42L },
        )
        // The stream dies on the second chunk (an expired URL's 403
        // looks exactly like this): honest FAILED, nothing kept.
        val failed = failingManager.runDownload(track())
        assertEquals(DownloadState.FAILED, failed?.state)
        assertFalse(File(downloadsDir, downloadFileName("vid1")).exists())
        assertFalse(File(downloadsDir, partialFileName(downloadFileName("vid1"))).exists())

        val goodManager = DownloadManager(
            store = store,
            downloadsDir = downloadsDir,
            resolveStream = resolve,
            fetcher = FakeFetcher(content(2500)),
            scope = deadScope(),
            chunkBytes = 1000L,
            nowMs = { 43L },
        )
        val done = goodManager.runDownload(track())
        assertEquals(DownloadState.DONE, done?.state)
        // The retry resolved AGAIN — a fresh stream, never the
        // expired URL the failed attempt was holding.
        assertEquals(2, resolves)
        assertArrayEquals(
            content(2500),
            File(downloadsDir, downloadFileName("vid1")).readBytes(),
        )
    }

    /** A fetcher that deletes the record mid-flight (a user delete / clear-all). */
    private class RecordEatingFetcher(
        private val store: DownloadStore,
        private val bytes: ByteArray,
    ) : ChunkFetcher {
        override fun fetchRange(url: String, window: LongRange): ByteArray {
            store.remove("vid1")
            return bytes.copyOfRange(window.first.toInt(), window.last.toInt() + 1)
        }

        override fun probeTotalBytes(url: String): Long? = null
    }

    @Test
    fun recordDeletedMidDownloadLeavesNoOrphanFile() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val downloadsDir = File(root, "downloads")
        store.enqueueIfAbsent(track(), nowMs = 1L)
        val manager = DownloadManager(
            store = store,
            downloadsDir = downloadsDir,
            resolveStream = { _, _ ->
                StreamInfo(
                    url = "https://example.invalid/stream",
                    mimeType = "audio/mp4",
                    bitrate = 128_000,
                    contentLength = 2500L,
                    approxDurationMs = 200_000L,
                )
            },
            fetcher = RecordEatingFetcher(store, content(2500)),
            scope = deadScope(),
            chunkBytes = 1000L,
            nowMs = { 42L },
        )
        val result = manager.runDownload(track())
        assertNull(result)
        assertNull(store.recordFor("vid1"))
        // Neither a final nor a partial file may outlive the record.
        assertFalse(File(downloadsDir, downloadFileName("vid1")).exists())
        assertFalse(File(downloadsDir, partialFileName(downloadFileName("vid1"))).exists())
    }

    /**
     * A fetcher that calls the manager's REAL delete — the Library
     * row's delete button (bug sweep round 38) — on its [deleteOnCall]-th
     * window: job cancelled, record removed, and BOTH files unlinked
     * while the run's output stream still holds the partial open.
     * The record-only simulations above cannot see the file side of
     * that landing: an unlink of an open file, a stream created
     * after the unlink, or a rename racing the deletes.
     */
    private class ManagerDeletingFetcher(
        private val bytes: ByteArray,
        private val deleteOnCall: Int,
        private val partFile: File,
    ) : ChunkFetcher {
        var manager: DownloadManager? = null
        var calls = 0
        var partExistedAtDelete: Boolean? = null
        var partLengthAtDelete: Long? = null

        override fun fetchRange(url: String, window: LongRange): ByteArray {
            calls++
            if (calls == deleteOnCall) {
                partExistedAtDelete = partFile.isFile
                partLengthAtDelete = if (partFile.isFile) partFile.length() else null
                manager!!.delete("vid1")
            }
            return bytes.copyOfRange(window.first.toInt(), window.last.toInt() + 1)
        }

        override fun probeTotalBytes(url: String): Long? = null
    }

    private fun streamInfo2500() = StreamInfo(
        url = "https://example.invalid/stream",
        mimeType = "audio/mp4",
        bitrate = 128_000,
        contentLength = 2500L,
        approxDurationMs = 200_000L,
    )

    private fun assertDownloadGone(store: DownloadStore, downloadsDir: File) {
        assertNull(store.recordFor("vid1"))
        assertFalse(File(downloadsDir, downloadFileName("vid1")).exists())
        assertFalse(File(downloadsDir, partialFileName(downloadFileName("vid1"))).exists())
        assertEquals(
            "no file of any name may outlive the delete",
            emptyList<File>(),
            downloadsDir.listFiles()?.toList() ?: emptyList<File>(),
        )
    }

    @Test
    fun uiDeleteMidWriteLeavesNoResurrectedRowOrOrphanPart() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val downloadsDir = File(root, "downloads")
        store.enqueueIfAbsent(track(), nowMs = 1L)
        val partFile = File(downloadsDir, partialFileName(downloadFileName("vid1")))
        val fetcher = ManagerDeletingFetcher(content(2500), deleteOnCall = 2, partFile = partFile)
        val manager = DownloadManager(
            store = store,
            downloadsDir = downloadsDir,
            resolveStream = { _, _ -> streamInfo2500() },
            fetcher = fetcher,
            scope = deadScope(),
            chunkBytes = 1000L,
            nowMs = { 42L },
        )
        fetcher.manager = manager
        val result = manager.runDownload(track())
        assertNull("a deleted download produces no outcome of its own", result)
        // The delete really landed mid-write: chunk 1 was on disk
        // under the partial's name when the unlink happened.
        assertEquals(true, fetcher.partExistedAtDelete)
        assertEquals(1000L, fetcher.partLengthAtDelete)
        assertDownloadGone(store, downloadsDir)
    }

    @Test
    fun uiDeleteOnFinalChunkLeavesNothing() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val downloadsDir = File(root, "downloads")
        store.enqueueIfAbsent(track(), nowMs = 1L)
        val partFile = File(downloadsDir, partialFileName(downloadFileName("vid1")))
        val fetcher = ManagerDeletingFetcher(content(2500), deleteOnCall = 3, partFile = partFile)
        val manager = DownloadManager(
            store = store,
            downloadsDir = downloadsDir,
            resolveStream = { _, _ -> streamInfo2500() },
            fetcher = fetcher,
            scope = deadScope(),
            chunkBytes = 1000L,
            nowMs = { 42L },
        )
        fetcher.manager = manager
        val result = manager.runDownload(track())
        assertNull(result)
        assertEquals(true, fetcher.partExistedAtDelete)
        assertEquals(2000L, fetcher.partLengthAtDelete)
        assertDownloadGone(store, downloadsDir)
    }

    @Test
    fun uiDeleteDuringResolveLeavesNothingAndNeverFetches() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val downloadsDir = File(root, "downloads")
        store.enqueueIfAbsent(track(), nowMs = 1L)
        val fetcher = ManagerDeletingFetcher(content(2500), deleteOnCall = -1, partFile = File("unused"))
        lateinit var manager: DownloadManager
        manager = DownloadManager(
            store = store,
            downloadsDir = downloadsDir,
            resolveStream = { _, _ ->
                manager.delete("vid1")
                streamInfo2500()
            },
            fetcher = fetcher,
            scope = deadScope(),
            chunkBytes = 1000L,
            nowMs = { 42L },
        )
        val result = manager.runDownload(track())
        assertNull(result)
        assertEquals("a run deleted during resolution must never reach the fetcher", 0, fetcher.calls)
        assertDownloadGone(store, downloadsDir)
    }

    /**
     * A fetcher that, on its LAST window, deletes the record and
     * re-enqueues a fresh one — the deterministic shape of the
     * owner's delete + immediate re-download tap landing while the
     * old run's bytes are still in flight (bug sweep round 7). The
     * fresh record is QUEUED and belongs to the fresh job; the stale
     * run must not stamp it with its own outcome.
     */
    private class DeleteAndReenqueueFetcher(
        private val store: DownloadStore,
        private val bytes: ByteArray,
        private val totalWindows: Int,
        private val failOnLast: Boolean,
    ) : ChunkFetcher {
        private var calls = 0

        override fun fetchRange(url: String, window: LongRange): ByteArray {
            calls++
            if (calls == totalWindows) {
                store.remove("vid1")
                store.enqueueIfAbsent(
                    Track(
                        videoId = "vid1",
                        title = "Title",
                        artists = listOf("Artist"),
                        artistBrowseId = null,
                        album = null,
                        albumBrowseId = null,
                        durationSeconds = 200,
                        thumbnailUrl = null,
                    ),
                    nowMs = 99L,
                )
                if (failOnLast) {
                    throw DownloadFailed("the stream server answered HTTP 500")
                }
            }
            return bytes.copyOfRange(window.first.toInt(), window.last.toInt() + 1)
        }

        override fun probeTotalBytes(url: String): Long? = null
    }

    @Test
    fun staleFailureDoesNotMarkTheFreshReenqueuedRecordFailed() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val downloadsDir = File(root, "downloads")
        store.enqueueIfAbsent(track(), nowMs = 1L)
        val manager = DownloadManager(
            store = store,
            downloadsDir = downloadsDir,
            resolveStream = { _, _ ->
                StreamInfo(
                    url = "https://example.invalid/stream",
                    mimeType = "audio/mp4",
                    bitrate = 128_000,
                    contentLength = 2500L,
                    approxDurationMs = 200_000L,
                )
            },
            fetcher = DeleteAndReenqueueFetcher(store, content(2500), totalWindows = 3, failOnLast = true),
            scope = deadScope(),
            chunkBytes = 1000L,
            nowMs = { 42L },
        )
        val result = manager.runDownload(track())
        assertNull("a stale run produces no outcome of its own", result)
        val fresh = store.recordFor("vid1")
        assertEquals(
            "the fresh re-enqueued record must stay QUEUED for its own job, " +
                "not be stamped FAILED with the stale run's reason",
            DownloadState.QUEUED,
            fresh?.state,
        )
        assertNull(fresh?.failureReason)
        assertEquals(99L, fresh?.updatedAtEpochMs)
    }

    @Test
    fun staleCompletionDoesNotMarkTheFreshReenqueuedRecordFailed() {
        // The delete + re-download lands AFTER the stale run's last
        // progress write, in the narrow window before its DONE
        // apply — driven deterministically through the injected
        // clock: the DONE apply's own nowMs() call performs the
        // delete + re-enqueue, exactly as the UI thread would in
        // that window. QUEUED -> DONE is an
        // illegal transition, so before the round-7 guard the DONE
        // attempt threw into the failure path, which destroyed the
        // finished file and stamped the fresh record FAILED.
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val downloadsDir = File(root, "downloads")
        store.enqueueIfAbsent(track(), nowMs = 1L)
        var flipArmed = false
        var armedCalls = 0
        var flipped = false
        val clock: () -> Long = {
            // The first post-arm call is the last chunk's progress
            // write (flipping there would be adopted by that write);
            // the second is the DONE apply's own timestamp — the
            // exact window this test exists to drive.
            if (flipArmed && !flipped) {
                armedCalls++
                if (armedCalls == 2) {
                    flipped = true
                    store.remove("vid1")
                    store.enqueueIfAbsent(track(), nowMs = 99L)
                }
            }
            42L
        }
        val fetcher = object : ChunkFetcher {
            private var calls = 0
            override fun fetchRange(url: String, window: LongRange): ByteArray {
                calls++
                if (calls == 3) flipArmed = true
                return content(2500).copyOfRange(window.first.toInt(), window.last.toInt() + 1)
            }

            override fun probeTotalBytes(url: String): Long? = null
        }
        val manager = DownloadManager(
            store = store,
            downloadsDir = downloadsDir,
            resolveStream = { _, _ ->
                StreamInfo(
                    url = "https://example.invalid/stream",
                    mimeType = "audio/mp4",
                    bitrate = 128_000,
                    contentLength = 2500L,
                    approxDurationMs = 200_000L,
                )
            },
            fetcher = fetcher,
            scope = deadScope(),
            chunkBytes = 1000L,
            nowMs = clock,
        )
        val result = manager.runDownload(track())
        assertTrue("the test must actually drive the interleaving", flipped)
        assertNull("a stale completion produces no outcome of its own", result)
        val fresh = store.recordFor("vid1")
        assertEquals(
            "the fresh re-enqueued record must stay QUEUED — not DONE by a run " +
                "that never owned it, and not FAILED via the illegal-transition throw",
            DownloadState.QUEUED,
            fresh?.state,
        )
        assertNull(fresh?.failureReason)
        // The stale run's finished file must not outlive its unwind.
        assertFalse(File(downloadsDir, downloadFileName("vid1")).exists())
    }

    private fun deadScope(): kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().apply { cancel() })

    /** A store holding one DONE record for vid1 at 2500 bytes, plus its manager. */
    private fun doneRig(fileBytes: Int?): Pair<DownloadStore, File> {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val downloadsDir = File(root, "downloads")
        store.enqueueIfAbsent(track(), nowMs = 1L)
        store.transition("vid1", DownloadState.DOWNLOADING, nowMs = 2L)
        store.transition(
            "vid1", DownloadState.DONE,
            downloadedBytes = 2500L, totalBytes = 2500L, mimeType = "audio/mp4", nowMs = 3L,
        )
        if (fileBytes != null) {
            downloadsDir.mkdirs()
            File(downloadsDir, downloadFileName("vid1")).writeBytes(content(fileBytes))
        }
        DownloadManager(
            store = store,
            downloadsDir = downloadsDir,
            resolveStream = { _, _ -> throw DownloadFailed("must not resolve in this test") },
            fetcher = FakeFetcher(ByteArray(0)),
            scope = deadScope(),
            nowMs = { 9L },
        )
        return store to downloadsDir
    }

    @Test
    fun doneRecordWithMissingOrTruncatedFileIsRequeuedOnManagerStart() {
        val (missingStore, _) = doneRig(fileBytes = null)
        assertEquals(DownloadState.QUEUED, missingStore.recordFor("vid1")?.state)
        assertEquals(0L, missingStore.recordFor("vid1")?.downloadedBytes)

        val (truncatedStore, _) = doneRig(fileBytes = 100)
        assertEquals(DownloadState.QUEUED, truncatedStore.recordFor("vid1")?.state)
    }

    @Test
    fun doneRecordWithIntactFileStaysDoneOnManagerStart() {
        val (store, _) = doneRig(fileBytes = 2500)
        assertEquals(DownloadState.DONE, store.recordFor("vid1")?.state)
    }

    @Test
    fun orphanSweepRemovesUntrackedFilesAndKeepsRecordedOnes() {
        val (store, downloadsDir) = doneRig(fileBytes = 2500)
        val orphan = File(downloadsDir, "orphan.audio").apply { writeBytes(content(10)) }
        val stalePart = File(downloadsDir, "ghost.audio.part").apply { writeBytes(content(10)) }
        // A fresh manager start performs the sweep.
        DownloadManager(
            store = store,
            downloadsDir = downloadsDir,
            resolveStream = { _, _ -> throw DownloadFailed("must not resolve in this test") },
            fetcher = FakeFetcher(ByteArray(0)),
            scope = deadScope(),
            nowMs = { 10L },
        )
        assertFalse(orphan.exists())
        assertFalse(stalePart.exists())
        assertTrue(File(downloadsDir, downloadFileName("vid1")).isFile)
        assertEquals(DownloadState.DONE, store.recordFor("vid1")?.state)
    }

    @Test
    fun orphanSweepRemovesCaseVariantPartialsToo() {
        // Round 4: the partial namespace is case-insensitive on
        // both sides — DownloadStore never keeps a `.PART` finished
        // name, and the sweep deletes partial leftovers in any
        // case, so the two can never disagree about a file.
        val (store, downloadsDir) = doneRig(fileBytes = 2500)
        val upperPart = File(downloadsDir, "ghost.audio.PART").apply { writeBytes(content(10)) }
        val mixedPart = File(downloadsDir, "ghost2.audio.Part").apply { writeBytes(content(10)) }
        DownloadManager(
            store = store,
            downloadsDir = downloadsDir,
            resolveStream = { _, _ -> throw DownloadFailed("must not resolve in this test") },
            fetcher = FakeFetcher(ByteArray(0)),
            scope = deadScope(),
            nowMs = { 10L },
        )
        assertFalse(upperPart.exists())
        assertFalse(mixedPart.exists())
        assertTrue(File(downloadsDir, downloadFileName("vid1")).isFile)
        assertEquals(DownloadState.DONE, store.recordFor("vid1")?.state)
    }

    @Test
    fun orphanSweepRemovesCaseVariantAudioOrphansToo() {
        // Round 5: round 4 made the sweep's `.part` match
        // case-insensitive but left `.audio` case-sensitive, so an
        // untracked `X.AUDIO` leftover was never swept and sat as
        // unaccounted storage forever — the same split invariant
        // in the sibling suffix.
        val (store, downloadsDir) = doneRig(fileBytes = 2500)
        val upperAudio = File(downloadsDir, "orphan.AUDIO").apply { writeBytes(content(10)) }
        val mixedAudio = File(downloadsDir, "orphan2.Audio").apply { writeBytes(content(10)) }
        DownloadManager(
            store = store,
            downloadsDir = downloadsDir,
            resolveStream = { _, _ -> throw DownloadFailed("must not resolve in this test") },
            fetcher = FakeFetcher(ByteArray(0)),
            scope = deadScope(),
            nowMs = { 10L },
        )
        assertFalse(upperAudio.exists())
        assertFalse(mixedAudio.exists())
        assertTrue(File(downloadsDir, downloadFileName("vid1")).isFile)
        assertEquals(DownloadState.DONE, store.recordFor("vid1")?.state)
    }

    @Test
    fun enqueueMissingCapsTheBatchPerRun() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val manager = DownloadManager(
            store = store,
            downloadsDir = File(root, "downloads"),
            resolveStream = { _, _ -> throw DownloadFailed("must not resolve in this test") },
            fetcher = FakeFetcher(ByteArray(0)),
            scope = deadScope(),
            nowMs = { 42L },
        )
        val tracks = (1..60).map { track("t$it") }
        val selected = manager.enqueueMissing(tracks)
        assertEquals(DownloadManager.MAX_AUTO_DOWNLOADS_PER_RUN, selected.size)
        assertEquals(DownloadManager.MAX_AUTO_DOWNLOADS_PER_RUN, store.records.value.size)
    }

    @Test
    fun enqueueMissingCoolsDownFreshFailuresButRetriesStaleOnes() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val now = 100_000_000L
        fun seedFailed(videoId: String, failedAtMs: Long) {
            store.enqueueIfAbsent(track(videoId), nowMs = 1L)
            store.transition(videoId, DownloadState.DOWNLOADING, nowMs = 2L)
            store.transition(
                videoId, DownloadState.FAILED,
                downloadedBytes = 0L, failureReason = "DownloadFailed: gone", nowMs = failedAtMs,
            )
        }
        seedFailed("fresh", now)
        seedFailed("stale", now - DownloadManager.FAILED_AUTO_RETRY_COOLDOWN_MS - 1L)
        val manager = DownloadManager(
            store = store,
            downloadsDir = File(root, "downloads"),
            resolveStream = { _, _ -> throw DownloadFailed("must not resolve in this test") },
            fetcher = FakeFetcher(ByteArray(0)),
            scope = deadScope(),
            nowMs = { now },
        )
        val selected = manager.enqueueMissing(listOf(track("fresh"), track("stale")))
        // The fresh failure waits out its cooldown (no re-fetch on
        // every app start); the stale one is honestly retried.
        assertEquals(listOf("stale"), selected.map { it.videoId })
        assertEquals(DownloadState.FAILED, store.recordFor("fresh")?.state)
        assertEquals(DownloadState.QUEUED, store.recordFor("stale")?.state)
    }

    @Test
    fun deleteAllRemovesEveryRecordAndFile() {
        val r = rig()
        r.store.enqueueIfAbsent(track("vid1"), nowMs = 1L)
        r.manager.runDownload(track("vid1"))
        r.store.enqueueIfAbsent(track("vid2"), nowMs = 1L)
        r.manager.runDownload(track("vid2"))
        File(r.downloadsDir, "orphan.audio").writeBytes(content(10))
        r.manager.deleteAll()
        assertEquals(emptyList<DownloadRecord>(), r.store.records.value)
        assertEquals(0L, r.manager.storageSummary().totalBytes)
        assertTrue(r.downloadsDir.listFiles()?.isEmpty() != false)
    }

    @Test
    fun contentRangeTotalParsing() {
        assertEquals(12345L, OkHttpChunkFetcher.parseContentRangeTotal("bytes 0-0/12345"))
        assertEquals(0L, OkHttpChunkFetcher.parseContentRangeTotal("bytes 0-0/0"))
        assertNull(OkHttpChunkFetcher.parseContentRangeTotal("bytes 0-0/*"))
        assertNull(OkHttpChunkFetcher.parseContentRangeTotal("bytes 0-0/"))
        assertNull(OkHttpChunkFetcher.parseContentRangeTotal("garbage"))
        assertNull(OkHttpChunkFetcher.parseContentRangeTotal(null))
    }
}
