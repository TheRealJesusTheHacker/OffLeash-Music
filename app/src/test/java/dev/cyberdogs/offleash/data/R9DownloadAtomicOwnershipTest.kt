package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.model.StreamInfo
import dev.cyberdogs.offleash.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Round 9 (break the round-8 ownership guard): round 8's
 * `transitionOwned` compared the standing record against the
 * record its run owned and then transitioned in a SECOND store
 * call. A delete + re-enqueue landing between the comparison
 * and the transition was invisible to the check — and QUEUED ->
 * DOWNLOADING being a legal move, the stale run's write adopted
 * the fresh record through that window: the same check-then-act
 * shape round 8 closed for the terminal applies with
 * `transitionIfState`, left open on every progress write. The
 * ownership check now lives inside the store's lock with the
 * transition (`transitionIfRecord`).
 */
class R9DownloadAtomicOwnershipTest {

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

    private fun content(total: Int): ByteArray = ByteArray(total) { (it % 251).toByte() }

    private fun streamInfo(): StreamInfo = StreamInfo(
        url = "https://example.invalid/stream",
        mimeType = "audio/mp4",
        bitrate = 128_000,
        contentLength = 2500L,
        approxDurationMs = 200_000L,
    )

    private fun deadScope(): kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().apply { cancel() })

    @Test
    fun staleWriteLandingInTheOwnershipWindowMustNotAdoptTheFreshRecord() {
        // The flip is driven through the injected clock, as in
        // round 8's terminal-window pin: the run's calls are
        // (1) enter DOWNLOADING, (2) resolution-info write,
        // (3) total write. The third call's nowMs() performs the
        // owner's delete + re-download tap — landing exactly
        // between the ownership comparison and the transition it
        // guards, in the pre-fix shape.
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val downloadsDir = File(root, "downloads")
        store.enqueueIfAbsent(track(), nowMs = 1L)
        var clockCalls = 0
        var flipped = false
        val clock: () -> Long = {
            clockCalls++
            if (clockCalls == 3 && !flipped) {
                flipped = true
                store.remove("vid1")
                store.enqueueIfAbsent(track(), nowMs = 99L)
            }
            42L
        }
        val fetcher = object : ChunkFetcher {
            val windows = mutableListOf<LongRange>()
            override fun fetchRange(url: String, window: LongRange): ByteArray {
                windows.add(window)
                return content(2500).copyOfRange(window.first.toInt(), window.last.toInt() + 1)
            }

            override fun probeTotalBytes(url: String): Long? = null
        }
        val manager = DownloadManager(
            store = store,
            downloadsDir = downloadsDir,
            resolveStream = { _, _ -> streamInfo() },
            fetcher = fetcher,
            scope = deadScope(),
            chunkBytes = 1000L,
            nowMs = clock,
        )
        val result = manager.runDownload(track())

        assertTrue("the test must actually drive the interleaving", flipped)
        assertNull("a run whose record changed hands in the window produces no outcome", result)
        assertEquals(
            "the stale run must stop at the window write, before any chunk is fetched",
            0,
            fetcher.windows.size,
        )
        val fresh = store.recordFor("vid1")
        assertEquals(
            "the fresh record must stand exactly as its own enqueue left it",
            DownloadState.QUEUED,
            fresh?.state,
        )
        assertEquals(99L, fresh?.updatedAtEpochMs)
        assertNull(fresh?.totalBytes)
        assertFalse(File(downloadsDir, downloadFileName("vid1")).exists())
        assertFalse(File(downloadsDir, partialFileName(downloadFileName("vid1"))).exists())
    }

    @Test
    fun transitionIfRecordRefusesAStandingRecordThatIsNotTheExpectedValue() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val owned = store.enqueueIfAbsent(track(), nowMs = 1L)
        // Another writer moves the record on: the value a stale
        // run holds is no longer the value standing.
        val moved = store.transition(
            videoId = "vid1",
            toState = DownloadState.DOWNLOADING,
            downloadedBytes = 0L,
            clearTotalBytes = true,
            nowMs = 2L,
        )
        val refused = store.transitionIfRecord(
            expected = owned,
            toState = DownloadState.DOWNLOADING,
            downloadedBytes = 500L,
            nowMs = 3L,
        )
        assertNull("a value-mismatched owned transition must refuse", refused)
        assertEquals(
            "the standing record is left exactly as it stands",
            moved,
            store.recordFor("vid1"),
        )
        // The matching value still transitions, atomically.
        val applied = store.transitionIfRecord(
            expected = moved!!,
            toState = DownloadState.DOWNLOADING,
            downloadedBytes = 500L,
            nowMs = 4L,
        )
        assertEquals(500L, applied?.downloadedBytes)
        assertEquals(4L, applied?.updatedAtEpochMs)
    }
}
