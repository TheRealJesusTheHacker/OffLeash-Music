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
 * Round 8 (break the round-7 DownloadManager ownership guard):
 * the terminal applies were guarded, but a stale run could still
 * ADOPT a fresh re-enqueued record mid-flight — every progress
 * write transitions whatever record currently stands for the
 * videoId, and QUEUED -> DOWNLOADING is legal, so the fresh record
 * became the stale run's record while the fresh job waited behind
 * the run mutex. The fresh job then re-ran from scratch, deleting
 * the finished file the stale run had just completed at its start
 * (the discard policy) — a completed download destroyed by its own
 * replacement, lost entirely when the fresh run then failed. The
 * isActive probe had the same identity blindness: it answers by
 * videoId, so the fresh job's registration made the cancelled
 * stale job read as "still active".
 */
class R8DownloadOwnershipTest {

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

    /**
     * A fetcher that, on its FIRST window, performs the owner's
     * delete + re-download tap at store level: the record is
     * removed and a fresh QUEUED record (updatedAt 99) stands in
     * its place while the stale run's bytes are still in flight.
     */
    private class FlipOnFirstWindowFetcher(
        private val store: DownloadStore,
        private val bytes: ByteArray,
        private val track: Track,
    ) : ChunkFetcher {
        val windows = mutableListOf<LongRange>()
        private var flipped = false

        override fun fetchRange(url: String, window: LongRange): ByteArray {
            windows.add(window)
            if (!flipped) {
                flipped = true
                store.remove(track.videoId)
                store.enqueueIfAbsent(track, nowMs = 99L)
            }
            return bytes.copyOfRange(window.first.toInt(), window.last.toInt() + 1)
        }

        override fun probeTotalBytes(url: String): Long? = null
    }

    @Test
    fun staleRunMustNotAdoptTheFreshReenqueuedRecord() {
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val downloadsDir = File(root, "downloads")
        store.enqueueIfAbsent(track(), nowMs = 1L)
        val fetcher = FlipOnFirstWindowFetcher(store, content(2500), track())
        val manager = DownloadManager(
            store = store,
            downloadsDir = downloadsDir,
            resolveStream = { _, _ -> streamInfo() },
            fetcher = fetcher,
            scope = deadScope(),
            chunkBytes = 1000L,
            nowMs = { 42L },
        )
        val result = manager.runDownload(track())

        assertNull("a stale run whose record was replaced produces no outcome", result)
        assertEquals(
            "the stale run must stop at the first progress write that " +
                "would adopt the fresh record, not fetch on to completion",
            1,
            fetcher.windows.size,
        )
        val fresh = store.recordFor("vid1")
        assertEquals(
            "the fresh record must stand exactly as its own enqueue left " +
                "it — QUEUED, unstamped by the stale run's progress or outcome",
            DownloadState.QUEUED,
            fresh?.state,
        )
        assertEquals(99L, fresh?.updatedAtEpochMs)
        assertEquals(0L, fresh?.downloadedBytes)
        assertNull(fresh?.totalBytes)
        assertFalse(File(downloadsDir, downloadFileName("vid1")).exists())
        assertFalse(File(downloadsDir, partialFileName(downloadFileName("vid1"))).exists())

        // The fresh job then runs and completes the download itself:
        // the track ends DONE with exactly its own bytes.
        val freshFetcher = object : ChunkFetcher {
            override fun fetchRange(url: String, window: LongRange): ByteArray =
                content(2500).copyOfRange(window.first.toInt(), window.last.toInt() + 1)

            override fun probeTotalBytes(url: String): Long? = null
        }
        val freshManager = DownloadManager(
            store = store,
            downloadsDir = downloadsDir,
            resolveStream = { _, _ -> streamInfo() },
            fetcher = freshFetcher,
            scope = deadScope(),
            chunkBytes = 1000L,
            nowMs = { 43L },
        )
        // A fresh manager's init launches QUEUED jobs on its scope;
        // the dead scope keeps that inert, and the direct call below
        // is the fresh job's run.
        val done = freshManager.runDownload(track())
        assertEquals(DownloadState.DONE, done?.state)
        assertTrue(
            File(downloadsDir, downloadFileName("vid1")).readBytes()
                .contentEquals(content(2500)),
        )
    }

    @Test
    fun launchJobActivityProbeAnswersForItsOwnJobNotTheVideoId() {
        // Contract half of the adoption fix: DownloadManager is
        // compiled by this harness, but launchJob's coroutine +
        // mutex choreography has no synchronous runtime seam, so
        // the probe's shape is the pin (house style). The probe
        // must compare the registered job against THIS job by
        // reference; the round-7 form answered by videoId, so the
        // fresh job's registration kept a cancelled stale job
        // reading "active" straight into the fresh record.
        val manager = File("src/main/java/dev/cyberdogs/offleash/data/DownloadManager.kt")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/dev/cyberdogs/offleash/data/DownloadManager.kt")
        val body = manager.readText()
            .substringAfter("private fun launchJob(track: Track)")
            .substringBefore("private fun isJobActive")
        assertTrue(
            "the activity probe must be job-identity based",
            body.contains("jobs[track.videoId] === mine"),
        )
        assertTrue(
            "the videoId-only probe must be gone from the job body",
            !body.contains("runDownload(track) { isJobActive(track.videoId) }"),
        )
    }

    @Test
    fun staleFailureApplyMustNotStampAFreshRecordLandingInItsWindow() {
        // Round 8: the round-7 failure guard read the record, chose
        // its branch, and THEN transitioned — two separate store
        // calls. A delete + re-enqueue landing between the guard
        // read and the transition found QUEUED -> FAILED a LEGAL
        // transition (unlike QUEUED -> DONE, which threw and was
        // caught), so the fresh record was stamped FAILED with the
        // stale run's reason after all — the exact round-7 harm
        // through the window the guard left open. The flip is
        // driven through the injected clock: the FAILED apply's
        // own nowMs() call performs the delete + re-enqueue, as
        // the UI thread would in that window.
        val root = temp.newFolder()
        val store = DownloadStore(File(root, "downloads.json"))
        val downloadsDir = File(root, "downloads")
        store.enqueueIfAbsent(track(), nowMs = 1L)
        var flipArmed = false
        var flipped = false
        val clock: () -> Long = {
            if (flipArmed && !flipped) {
                flipped = true
                store.remove("vid1")
                store.enqueueIfAbsent(track(), nowMs = 99L)
            }
            42L
        }
        val fetcher = object : ChunkFetcher {
            private var calls = 0
            override fun fetchRange(url: String, window: LongRange): ByteArray {
                calls++
                if (calls == 3) {
                    flipArmed = true
                    throw DownloadFailed("the stream server answered HTTP 500")
                }
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
        assertNull("a stale failure produces no outcome of its own", result)
        val fresh = store.recordFor("vid1")
        assertEquals(
            "the fresh record must stay QUEUED — the stale run's FAILED " +
                "stamp must not land through the guard's window",
            DownloadState.QUEUED,
            fresh?.state,
        )
        assertNull(fresh?.failureReason)
        assertEquals(99L, fresh?.updatedAtEpochMs)
    }
}
