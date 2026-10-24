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
