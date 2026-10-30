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

/**
 * Round 70 pin (adversarial lane, charter 2): the job-cancellation
 * leg of [DownloadManager.cancel].
 *
 * `cancel(videoId)` neither removes the job from the jobs map nor
 * touches the record — its `?.cancel()` on the registered job is
 * the ONLY stop it has. (Its siblings are different: `delete` and
 * `deleteAll` also remove the job from the map, which flips
 * runDownload's identity probe — `jobs[videoId] === mine` — and
 * aborts the run at the next chunk boundary even with their
 * cancel dropped; round 70 probed both siblings both ways and
 * their end state is identical with and without the cancel.)
 *
 * Dropping cancel's leg ran GREEN across DownloadManagerTest and
 * DownloadsTest (69 tests) — no test had ever called
 * `manager.cancel()` on a live job. The probe this pin ships
 * from proved the behaviour both ways: with the leg dropped, a
 * user-cancelled in-flight download fetches its remaining
 * windows and lands DONE with its file on disk; shipped, it
 * stops at the chunk boundary, records a named cancellation,
 * and leaves no file.
 */
class R70DownloadCancelPinTest {

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

    /** A fetcher parked inside its first window until released. */
    private class BlockingFetcher(
        private val bytes: ByteArray,
    ) : ChunkFetcher {
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

    private fun awaitTrue(timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    @Test
    fun cancelStopsAnInFlightDownloadAtTheChunkBoundary() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val downloadsDir = File(root, "downloads")
        val fetcher = BlockingFetcher(ByteArray(2500) { (it % 251).toByte() })
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
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            chunkBytes = 1000L,
            nowMs = { 42L },
        )
        manager.enqueue(track())
        assertTrue(
            "the download must be inside its first window before the cancel",
            fetcher.entered.await(10, TimeUnit.SECONDS),
        )
        manager.cancel("vid1")
        fetcher.release.countDown()
        awaitTrue { store.recordFor("vid1")?.state == DownloadState.FAILED }
        assertEquals(
            "a cancelled download must not fetch another window — " +
                "the job cancel is the only thing that stops it",
            1,
            fetcher.windows.size,
        )
        assertTrue(
            "the cancelled record must name its cancellation",
            store.recordFor("vid1")?.failureReason?.contains("cancelled") == true,
        )
        assertFalse(
            "no finished file may survive a cancel",
            File(downloadsDir, downloadFileName("vid1")).exists(),
        )
    }
}
