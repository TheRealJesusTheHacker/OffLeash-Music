package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The pure download decisions (Lane F): the state machine (including
 * failure naming), the bounded chunk planner, storage accounting,
 * the local-playback precedence decision, file naming, byte
 * formatting, progress, auto-download selection, and the record
 * store's persistence round-trip.
 */
class DownloadsTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private fun track(videoId: String): Track = Track(
        videoId = videoId,
        title = "Title $videoId",
        artists = listOf("Artist"),
        artistBrowseId = null,
        album = null,
        albumBrowseId = null,
        durationSeconds = 200,
        thumbnailUrl = null,
    )

    private fun record(
        videoId: String,
        state: DownloadState,
        downloadedBytes: Long = 0L,
        totalBytes: Long? = null,
        failureReason: String? = if (state == DownloadState.FAILED) "NetworkFailure: offline" else null,
    ): DownloadRecord = DownloadRecord(
        track = track(videoId),
        state = state,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        failureReason = failureReason,
    )

    // -- State machine --------------------------------------------------------

    @Test
    fun legalTransitionsAreAllowed() {
        val queued = record("a", DownloadState.QUEUED)
        val downloading = DownloadStateMachine.transition(queued, DownloadState.DOWNLOADING)
        val done = DownloadStateMachine.transition(downloading, DownloadState.DONE)
        assertEquals(DownloadState.DONE, done.state)
        // A finished download may be explicitly re-queued.
        assertEquals(
            DownloadState.QUEUED,
            DownloadStateMachine.transition(done, DownloadState.QUEUED).state,
        )
        // Cancelling back to the queue is legal from DOWNLOADING.
        assertEquals(
            DownloadState.QUEUED,
            DownloadStateMachine.transition(downloading, DownloadState.QUEUED).state,
        )
        // Retry: FAILED passes through QUEUED (and directly to DOWNLOADING).
        val failed = DownloadStateMachine.transition(
            downloading, DownloadState.FAILED, failureReason = "NetworkFailure: offline",
        )
        assertEquals(
            DownloadState.QUEUED,
            DownloadStateMachine.transition(failed, DownloadState.QUEUED).state,
        )
        assertEquals(
            DownloadState.DOWNLOADING,
            DownloadStateMachine.transition(failed, DownloadState.DOWNLOADING).state,
        )
        // Self-transitions (progress updates) are legal everywhere.
        for (state in DownloadState.entries) {
            assertTrue(DownloadStateMachine.canTransition(state, state))
        }
    }

    @Test
    fun illegalTransitionsAreRefused() {
        assertFalse(DownloadStateMachine.canTransition(DownloadState.QUEUED, DownloadState.DONE))
        assertFalse(DownloadStateMachine.canTransition(DownloadState.DONE, DownloadState.DOWNLOADING))
        assertFalse(DownloadStateMachine.canTransition(DownloadState.DONE, DownloadState.FAILED))
        assertThrows(IllegalArgumentException::class.java) {
            DownloadStateMachine.transition(
                record("a", DownloadState.QUEUED), DownloadState.DONE,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadStateMachine.transition(
                record("a", DownloadState.DONE), DownloadState.DOWNLOADING,
            )
        }
    }

    @Test
    fun failedRequiresANameAndOtherStatesClearIt() {
        val downloading = record("a", DownloadState.DOWNLOADING)
        assertThrows(IllegalArgumentException::class.java) {
            DownloadStateMachine.transition(downloading, DownloadState.FAILED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadStateMachine.transition(
                downloading, DownloadState.FAILED, failureReason = "  ",
            )
        }
        val failed = DownloadStateMachine.transition(
            downloading, DownloadState.FAILED, failureReason = "NetworkFailure: offline",
        )
        assertEquals("NetworkFailure: offline", failed.failureReason)
        val retried = DownloadStateMachine.transition(failed, DownloadState.QUEUED)
        assertNull(retried.failureReason)
    }

    @Test
    fun failureReasonNamesTheErrorClassAndMessage() {
        assertEquals(
            "DownloadFailed: no size",
            downloadFailureReason(DownloadFailed("no size")),
        )
        assertEquals("IllegalStateException", downloadFailureReason(IllegalStateException()))
    }

    // -- Chunk planner ---------------------------------------------------------

    @Test
    fun plannerCoversAFileExactlyWithBoundedWindows() {
        val cases = listOf(
            1L to 1000L,
            999L to 1000L,
            1000L to 1000L,
            1001L to 1000L,
            2500L to 1000L,
            3L * 1024L * 1024L + 7L to DownloadChunkPlanner.DEFAULT_CHUNK_BYTES,
        )
        for ((total, chunk) in cases) {
            val windows = DownloadChunkPlanner.plan(total, chunk)
            // Exact cover: contiguous from 0, last end at total-1, sizes sum to total.
            assertEquals(0L, windows.first().first)
            assertEquals(total - 1L, windows.last().last)
            assertEquals(total, windows.sumOf { DownloadChunkPlanner.windowSize(it) })
            for (i in 1 until windows.size) {
                assertEquals(windows[i - 1].last + 1L, windows[i].first)
            }
            // Every window bounded and no larger than the chunk size.
            for (window in windows) {
                assertTrue(window.last >= window.first)
                assertTrue(window.last < total)
                assertTrue(DownloadChunkPlanner.windowSize(window) <= chunk)
            }
        }
    }

    @Test
    fun plannerEdgeCases() {
        assertEquals(emptyList<LongRange>(), DownloadChunkPlanner.plan(0L, 1000L))
        assertEquals(
            listOf(0L..1048575L),
            DownloadChunkPlanner.plan(DownloadChunkPlanner.DEFAULT_CHUNK_BYTES),
        )
        assertEquals(
            listOf(0L..999L, 1000L..1999L, 2000L..2499L),
            DownloadChunkPlanner.plan(2500L, 1000L),
        )
        assertThrows(IllegalArgumentException::class.java) {
            DownloadChunkPlanner.plan(-1L, 1000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadChunkPlanner.plan(10L, 0L)
        }
    }

    // -- Storage accounting ------------------------------------------------------

    @Test
    fun accountingCountsOnlyDoneFilesAtTheirActualLength() {
        val records = listOf(
            record("done1", DownloadState.DONE, downloadedBytes = 5000L, totalBytes = 5000L),
            record("done2", DownloadState.DONE, downloadedBytes = 3000L, totalBytes = 3000L),
            record("gone", DownloadState.DONE, downloadedBytes = 9000L, totalBytes = 9000L),
            record("q", DownloadState.QUEUED),
            record("d", DownloadState.DOWNLOADING, downloadedBytes = 1234L, totalBytes = 9999L),
            record("f", DownloadState.FAILED),
        )
        val lengths = mapOf("done1" to 5000L, "done2" to 2500L, "gone" to null)
        val summary = summariseDownloads(records) { lengths[it.videoId] }
        // done2 contributes its ACTUAL (shorter) file length, the
        // missing file contributes 0, in-progress bytes contribute 0.
        assertEquals(7500L, summary.totalBytes)
        assertEquals(3, summary.downloadedCount)
        assertEquals(1, summary.queuedCount)
        assertEquals(1, summary.downloadingCount)
        assertEquals(1, summary.failedCount)
    }

    @Test
    fun accountingOfNothingIsZero() {
        val summary = summariseDownloads(emptyList()) { null }
        assertEquals(0L, summary.totalBytes)
        assertEquals(0, summary.downloadedCount)
    }

    // -- Playback precedence -------------------------------------------------------

    @Test
    fun doneDownloadWithARealFileWins() {
        val dir = temp.newFolder("downloads")
        val done = record("a", DownloadState.DONE, totalBytes = 10L)
            .copy(mimeType = "audio/mp4")
        val playback = downloadedPlaybackFor(done, dir) { file ->
            if (file.name == done.fileName) 10L else null
        }
        assertEquals(File(dir, done.fileName), playback?.file)
        assertEquals("audio/mp4", playback?.mimeType)
    }

    @Test
    fun anythingButDoneWithARealFileFallsBackToNetwork() {
        val dir = temp.newFolder("downloads")
        for (state in listOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.FAILED)) {
            assertNull(downloadedPlaybackFor(record("a", state), dir) { 10L })
        }
        val done = record("a", DownloadState.DONE, totalBytes = 10L)
        // Missing file, and zero-length file, both fall back.
        assertNull(downloadedPlaybackFor(done, dir) { null })
        assertNull(downloadedPlaybackFor(done, dir) { 0L })
        assertNull(downloadedPlaybackFor(null, dir) { 10L })
    }

    // -- File naming, formatting, progress -----------------------------------------

    @Test
    fun fileNamesAreSanitisedAndPartialIsDistinct() {
        assertEquals("abc-DEF_123.audio", downloadFileName("abc-DEF_123"))
        assertEquals("a_b_c_d.audio", downloadFileName("a/b:c d"))
        assertEquals("track.audio", downloadFileName(""))
        assertEquals("x.audio.part", partialFileName("x.audio"))
    }

    @Test
    fun byteFormattingIsPlain() {
        assertEquals("0 B", formatDownloadBytes(0L))
        assertEquals("512 B", formatDownloadBytes(512L))
        assertEquals("1 KB", formatDownloadBytes(1024L))
        assertEquals("1.5 KB", formatDownloadBytes(1536L))
        assertEquals("1 MB", formatDownloadBytes(1024L * 1024L))
        assertEquals("1.4 MB", formatDownloadBytes(1_500_000L))
        assertEquals("0 B", formatDownloadBytes(-5L))
    }

    @Test
    fun progressPercentIsNullUntilTheSizeIsKnown() {
        assertNull(downloadProgressPercent(record("a", DownloadState.QUEUED)))
        assertNull(downloadProgressPercent(record("a", DownloadState.DOWNLOADING, totalBytes = 0L)))
        assertEquals(
            50,
            downloadProgressPercent(
                record("a", DownloadState.DOWNLOADING, downloadedBytes = 500L, totalBytes = 1000L),
            ),
        )
        assertEquals(
            100,
            downloadProgressPercent(
                record("a", DownloadState.DOWNLOADING, downloadedBytes = 5000L, totalBytes = 1000L),
            ),
        )
        assertEquals(
            100,
            downloadProgressPercent(
                record("a", DownloadState.DONE, downloadedBytes = 10L, totalBytes = 1000L),
            ),
        )
    }

    // -- Auto-download selection ------------------------------------------------------

    @Test
    fun autoDownloadSelectsNewAndFailedOnlyOnceEach() {
        val tracks = listOf(
            track("new"), track("done"), track("queued"),
            track("downloading"), track("failed"), track("new"),
        )
        val records = listOf(
            record("done", DownloadState.DONE),
            record("queued", DownloadState.QUEUED),
            record("downloading", DownloadState.DOWNLOADING),
            record("failed", DownloadState.FAILED),
        )
        val selected = tracksToAutoDownload(tracks, records).map { it.videoId }
        assertEquals(listOf("new", "failed"), selected)
    }

    // -- Store persistence --------------------------------------------------------------

    @Test
    fun storeRoundTripsRecordsThroughDisk() {
        val file = File(temp.newFolder("store"), "downloads.json")
        val store = DownloadStore(file)
        val queued = store.enqueueIfAbsent(track("a"), nowMs = 100L)
        assertEquals(DownloadState.QUEUED, queued.state)
        // Enqueue-if-absent is idempotent.
        assertEquals(queued, store.enqueueIfAbsent(track("a"), nowMs = 200L))

        store.transition(
            "a", DownloadState.DOWNLOADING, downloadedBytes = 5L, totalBytes = 10L, nowMs = 300L,
        )
        val done = store.transition(
            "a", DownloadState.DONE,
            downloadedBytes = 10L, totalBytes = 10L, mimeType = "audio/mp4", nowMs = 400L,
        )
        assertEquals(DownloadState.DONE, done?.state)

        val reloaded = DownloadStore(file)
        assertEquals(listOf(done), reloaded.records.value)
        assertEquals(done, reloaded.recordFor("a"))

        reloaded.remove("a")
        assertEquals(emptyList<DownloadRecord>(), DownloadStore(file).records.value)
    }

    @Test
    fun storeTransitionOfAMissingRecordIsNullAndCorruptionReadsEmpty() {
        val file = File(temp.newFolder("store2"), "downloads.json")
        val store = DownloadStore(file)
        assertNull(store.transition("ghost", DownloadState.DOWNLOADING, nowMs = 1L))

        file.parentFile?.mkdirs()
        file.writeText("this is not json")
        assertEquals(emptyList<DownloadRecord>(), DownloadStore(file).records.value)
    }
}
