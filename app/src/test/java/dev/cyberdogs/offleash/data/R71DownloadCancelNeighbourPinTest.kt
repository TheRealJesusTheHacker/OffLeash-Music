package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.model.StreamInfo
import dev.cyberdogs.offleash.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Round 71 pins (adversarial lane, charter 2): the neighbours of
 * round 70's DownloadManager.cancel() latch pin, constructed as
 * probes first — two of the four were genuine defects on the
 * shipped code:
 *
 *  - A cancel landing during the FINAL window's fetch was
 *    silently overridden: [DownloadManager.runDownload] consulted
 *    its isActive probe before the resolve and before each chunk,
 *    but never on the completion path, so the run landed DONE
 *    with its file over the owner's cancel. The completion path
 *    now re-checks the probe before the size check.
 *
 *  - A cancel of a QUEUED (never-started) record stranded it:
 *    its job, cancelled while parked on the run mutex, never
 *    runs, so no run ever recorded the cancellation — the record
 *    sat QUEUED with no live job until the next process start.
 *    cancel() now records the FAILED itself, through the
 *    conditional transition, when the record is still QUEUED.
 *
 * The other two neighbours were benign and are pinned as the
 * regression shape: a second cancel() is a no-op on the same end
 * state, and enqueue after a settled cancel re-downloads.
 *
 * Round 72 added three more neighbours below: a cancel landing
 * while the DONE stamp is prepared (after round 71's re-check —
 * a genuine third defect in the same class, fixed by re-running
 * the cancel gate as the stamp's final word), a cancel landing
 * after a real failure (benign: the real reason stands), and a
 * double cancel of a QUEUED record (benign: the round-71 stamp
 * is state-gated, so the second cancel is a no-op).
 */
class R71DownloadCancelNeighbourPinTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private fun track(videoId: String = "vid1"): Track = Track(
        videoId = videoId, title = "Title", artists = listOf("Artist"),
        artistBrowseId = null, album = null, albumBrowseId = null,
        durationSeconds = 200, thumbnailUrl = null,
    )

    private fun streamInfo(): StreamInfo = StreamInfo(
        url = "https://example.invalid/stream", mimeType = "audio/mp4",
        bitrate = 128_000, contentLength = 2500L, approxDurationMs = 200_000L,
    )

    private fun awaitTrue(timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    /** Fetcher parked inside its first window until released. */
    private class BlockingFetcher(private val bytes: ByteArray) : ChunkFetcher {
        val windows = CopyOnWriteArrayList<LongRange>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun fetchRange(url: String, window: LongRange): ByteArray {
            windows.add(window)
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            return bytes.copyOfRange(window.first.toInt(), window.last.toInt() + 1)
        }
        override fun probeTotalBytes(url: String): Long = bytes.size.toLong()
    }

    /** Instant fetcher; flags when its final window was fetched. */
    private class FlagFetcher(private val bytes: ByteArray) : ChunkFetcher {
        val windows = CopyOnWriteArrayList<LongRange>()
        @Volatile var lastWindowSeen = false
        override fun fetchRange(url: String, window: LongRange): ByteArray {
            windows.add(window)
            if (window.last == bytes.size - 1L) lastWindowSeen = true
            return bytes.copyOfRange(window.first.toInt(), window.last.toInt() + 1)
        }
        override fun probeTotalBytes(url: String): Long = bytes.size.toLong()
    }

    /** Fetcher whose every window fails with a real, named failure. */
    private class FailingFetcher : ChunkFetcher {
        override fun fetchRange(url: String, window: LongRange): ByteArray =
            throw DownloadFailed(
                "the stream server answered HTTP 403 instead of a partial range (206)",
            )
        override fun probeTotalBytes(url: String): Long = 2500L
    }

    /** Fetcher that cancels the download from inside its LAST window. */
    private class CompletionWindowFetcher(
        private val bytes: ByteArray,
    ) : ChunkFetcher {
        val windows = CopyOnWriteArrayList<LongRange>()
        var onLastWindow: (() -> Unit)? = null
        override fun fetchRange(url: String, window: LongRange): ByteArray {
            windows.add(window)
            if (window.last == bytes.size - 1L) onLastWindow?.invoke()
            return bytes.copyOfRange(window.first.toInt(), window.last.toInt() + 1)
        }
        override fun probeTotalBytes(url: String): Long = bytes.size.toLong()
    }

    private fun manager(
        store: DownloadStore, dir: File, fetcher: ChunkFetcher,
    ): DownloadManager = DownloadManager(
        store = store, downloadsDir = dir,
        resolveStream = { _, _ -> streamInfo() },
        fetcher = fetcher,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        chunkBytes = 1000L, nowMs = { 42L },
    )

    @Test
    fun cancelTwiceKeepsTheSingleCancelEndState() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val dir = File(root, "downloads")
        val fetcher = BlockingFetcher(ByteArray(2500) { (it % 251).toByte() })
        val manager = manager(store, dir, fetcher)
        manager.enqueue(track())
        assertTrue(fetcher.entered.await(10, TimeUnit.SECONDS))
        manager.cancel("vid1")
        manager.cancel("vid1")
        fetcher.release.countDown()
        awaitTrue { store.recordFor("vid1")?.state == DownloadState.FAILED }
        assertEquals(1, fetcher.windows.size)
        assertTrue(
            store.recordFor("vid1")?.failureReason?.contains("cancelled") == true,
        )
        assertFalse(File(dir, downloadFileName("vid1")).exists())
    }

    @Test
    fun cancelDuringTheFinalWindowStillStopsTheDownload() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val dir = File(root, "downloads")
        val fetcher = CompletionWindowFetcher(ByteArray(2500) { (it % 251).toByte() })
        val manager = manager(store, dir, fetcher)
        fetcher.onLastWindow = { manager.cancel("vid1") }
        manager.enqueue(track())
        awaitTrue { store.recordFor("vid1")?.state == DownloadState.FAILED }
        assertEquals(
            "all three windows were fetched — the cancel landed " +
                "inside the last one, and the completion path must " +
                "still honour it instead of landing DONE",
            3,
            fetcher.windows.size,
        )
        assertTrue(
            store.recordFor("vid1")?.failureReason?.contains("cancelled") == true,
        )
        assertFalse(
            "no finished file may survive a cancel, even a late one",
            File(dir, downloadFileName("vid1")).exists(),
        )
        assertFalse(
            "no partial may survive a cancel either (discard policy)",
            File(dir, partialFileName(downloadFileName("vid1"))).exists(),
        )
    }

    @Test
    fun cancelOfAQueuedRecordRecordsTheCancellation() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val dir = File(root, "downloads")
        val fetcher = BlockingFetcher(ByteArray(2500) { (it % 251).toByte() })
        val manager = manager(store, dir, fetcher)
        manager.enqueue(track("vid1"))
        assertTrue(fetcher.entered.await(10, TimeUnit.SECONDS))
        manager.enqueue(track("vid2"))
        assertEquals(DownloadState.QUEUED, store.recordFor("vid2")?.state)
        manager.cancel("vid2")
        assertEquals(
            "a queued record's cancelled job never runs, so cancel " +
                "itself must record the cancellation — a QUEUED " +
                "record with no live job is stranded in-session",
            DownloadState.FAILED,
            store.recordFor("vid2")?.state,
        )
        assertTrue(
            store.recordFor("vid2")?.failureReason?.contains("cancelled") == true,
        )
        fetcher.release.countDown()
        awaitTrue { store.recordFor("vid1")?.state == DownloadState.DONE }
        Thread.sleep(500)
        assertEquals(
            "the cancelled queued download must never start",
            3,
            fetcher.windows.size,
        )
        assertEquals(DownloadState.FAILED, store.recordFor("vid2")?.state)
        // And the record stays retryable, like any cancelled one:
        manager.enqueue(track("vid2"))
        awaitTrue { store.recordFor("vid2")?.state == DownloadState.DONE }
        assertEquals(6, fetcher.windows.size)
    }

    // -- Round 72 neighbours of the round-71 completion fix -----
    // Round 71's re-check guards the size check and the rename,
    // but the DONE stamp is prepared after it (its timestamp is
    // evaluated as part of the stamp call): a cancel landing in
    // that preparation window — after the re-check, before the
    // stamp — still landed DONE over the owner's cancel on the
    // round-71 code (probe-proven through the injected clock,
    // the one seam a cancel thread needs no timing luck to hit
    // in this construction). The stamp now takes its timestamp
    // FIRST and re-runs the cancel gate as the final word
    // before the stamp. The probe discriminator is honest both
    // ways: firing at the FIRST post-final-window clock call
    // (the final chunk's progress transition) is caught by
    // round 71's re-check, and firing at the SECOND (the stamp
    // preparation) is what this pin constructs.

    @Test
    fun cancelLandingWhileTheDoneStampIsPreparedStillStopsTheDownload() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val dir = File(root, "downloads")
        val fetcher = FlagFetcher(ByteArray(2500) { (it % 251).toByte() })
        val active = AtomicBoolean(true)
        val postFlagCalls = AtomicInteger(0)
        var managerRef: DownloadManager? = null
        val clock: () -> Long = {
            if (fetcher.lastWindowSeen) {
                val n = postFlagCalls.incrementAndGet()
                if (n == 2) {
                    // The stamp-preparation call: the cancel
                    // lands here, after round 71's re-check.
                    active.set(false)
                    managerRef?.cancel("vid1")
                }
            }
            42L
        }
        val manager = DownloadManager(
            store = store, downloadsDir = dir,
            resolveStream = { _, _ -> streamInfo() },
            fetcher = fetcher,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            chunkBytes = 1000L, nowMs = clock,
        )
        managerRef = manager
        store.enqueueIfAbsent(track(), 1L)
        val result = manager.runDownload(track()) { active.get() }
        assertEquals(
            "a cancel landing while the DONE stamp is prepared " +
                "must still win — the run unwinds FAILED, never DONE",
            DownloadState.FAILED,
            result?.state,
        )
        assertEquals(DownloadState.FAILED, store.recordFor("vid1")?.state)
        assertTrue(
            store.recordFor("vid1")?.failureReason?.contains("cancelled") == true,
        )
        assertFalse(
            "the renamed finished file is discarded with the run",
            File(dir, downloadFileName("vid1")).exists(),
        )
        assertFalse(
            File(dir, partialFileName(downloadFileName("vid1"))).exists(),
        )
    }

    @Test
    fun cancelAfterARealFailureKeepsTheRealReason() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val dir = File(root, "downloads")
        val manager = DownloadManager(
            store = store, downloadsDir = dir,
            resolveStream = { _, _ -> streamInfo() },
            fetcher = FailingFetcher(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            chunkBytes = 1000L, nowMs = { 42L },
        )
        manager.enqueue(track())
        awaitTrue { store.recordFor("vid1")?.state == DownloadState.FAILED }
        val reasonBefore = store.recordFor("vid1")?.failureReason
        assertTrue(reasonBefore?.contains("403") == true)
        manager.cancel("vid1")
        assertEquals(
            "a cancel of an already-FAILED record must not rewrite " +
                "its failure as a cancellation — the record's own " +
                "history is the honest row",
            reasonBefore,
            store.recordFor("vid1")?.failureReason,
        )
        assertEquals(DownloadState.FAILED, store.recordFor("vid1")?.state)
    }

    @Test
    fun cancelTwiceOfAQueuedRecordKeepsTheSingleCancelEndState() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val dir = File(root, "downloads")
        val fetcher = BlockingFetcher(ByteArray(2500) { (it % 251).toByte() })
        val manager = manager(store, dir, fetcher)
        manager.enqueue(track("vid1"))
        assertTrue(fetcher.entered.await(10, TimeUnit.SECONDS))
        manager.enqueue(track("vid2"))
        assertEquals(DownloadState.QUEUED, store.recordFor("vid2")?.state)
        manager.cancel("vid2")
        manager.cancel("vid2")
        assertEquals(
            "the second cancel of a queued record is a no-op on " +
                "the first cancel's end state (the round-71 stamp " +
                "is state-gated on QUEUED)",
            DownloadState.FAILED,
            store.recordFor("vid2")?.state,
        )
        assertTrue(
            store.recordFor("vid2")?.failureReason?.contains("cancelled") == true,
        )
        fetcher.release.countDown()
        awaitTrue { store.recordFor("vid1")?.state == DownloadState.DONE }
        Thread.sleep(500)
        assertEquals(DownloadState.FAILED, store.recordFor("vid2")?.state)
        assertEquals(3, fetcher.windows.size)
    }

    @Test
    fun enqueueAfterASettledCancelRedownloads() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val dir = File(root, "downloads")
        val fetcher = BlockingFetcher(ByteArray(2500) { (it % 251).toByte() })
        val manager = manager(store, dir, fetcher)
        manager.enqueue(track())
        assertTrue(fetcher.entered.await(10, TimeUnit.SECONDS))
        manager.cancel("vid1")
        fetcher.release.countDown()
        awaitTrue { store.recordFor("vid1")?.state == DownloadState.FAILED }
        manager.enqueue(track())
        awaitTrue { store.recordFor("vid1")?.state == DownloadState.DONE }
        assertEquals(4, fetcher.windows.size)
        assertTrue(File(dir, downloadFileName("vid1")).exists())
    }
}
