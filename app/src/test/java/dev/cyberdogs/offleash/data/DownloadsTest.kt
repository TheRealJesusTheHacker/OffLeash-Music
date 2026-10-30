package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.error.AllRoadsRefused
import dev.cyberdogs.offleash.core.error.ContextRejected
import dev.cyberdogs.offleash.core.error.NetworkFailure
import dev.cyberdogs.offleash.core.error.NoPlayableStream
import dev.cyberdogs.offleash.core.error.ParseFailure
import dev.cyberdogs.offleash.core.error.PlayerRefused
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

    @Test
    fun truncatedOrSwollenDoneFileFallsBackToNetwork() {
        val dir = temp.newFolder("downloads")
        val done = record("a", DownloadState.DONE, downloadedBytes = 2500L, totalBytes = 2500L)
        // A file trimmed behind the app's back must not reach the
        // player to stall on — playback falls back to the network.
        assertNull(downloadedPlaybackFor(done, dir) { 100L })
        assertNull(downloadedPlaybackFor(done, dir) { 2600L })
        // The exact recorded size still wins.
        assertEquals(
            File(dir, done.fileName),
            downloadedPlaybackFor(done, dir) { 2500L }?.file,
        )
        // A legacy record with no recorded size accepts any real file.
        val legacy = record("a", DownloadState.DONE, downloadedBytes = 100L, totalBytes = null)
        assertEquals(
            File(dir, legacy.fileName),
            downloadedPlaybackFor(legacy, dir) { 100L }?.file,
        )
    }

    @Test
    fun failureReasonNeverCarriesALinkAndIsCapped() {
        // The reason is persisted and shown in the UI; a resolver or
        // transport message can embed the signed stream URL, which
        // is a secret and must never survive into it.
        val reason = downloadFailureReason(
            IllegalStateException(
                "could not fetch https://googlevideo.example/videoplayback?expire=1&sig=SECRET for the track",
            ),
        )
        assertFalse(reason.contains("http"))
        assertFalse(reason.contains("SECRET"))
        assertTrue(reason.contains("[link removed]"))
        val long = downloadFailureReason(IllegalStateException("x".repeat(1000)))
        assertTrue(long.length <= 300)
    }

    // Round 24: a core failure's MESSAGE embeds raw server or
    // transport text (ContextRejected carries the server's own
    // words; NetworkFailure carries the transport detail, which
    // can name a host/address; AllRoadsRefused nests both roads'
    // messages). The reason is persisted and shown in the Library,
    // so a core failure must be described from its TYPE alone —
    // the round-6 lyrics rule — never quoted.
    @Test
    fun failureReasonForCoreRefusalNeverQuotesTheServer() {
        val reason = downloadFailureReason(
            ContextRejected("RAW-SERVER-WORDING https://signed.example/x?sig=SECRET"),
        )
        assertTrue(reason.startsWith("ContextRejected: "))
        assertFalse(reason.contains("RAW-SERVER-WORDING"))
        assertFalse(reason.contains("SECRET"))
        assertFalse(reason.contains("http"))
    }

    @Test
    fun failureReasonForNetworkFailureNeverQuotesTransportDetail() {
        val reason = downloadFailureReason(
            NetworkFailure("Unable to resolve host \"music.youtube.com\": 203.0.113.7"),
        )
        assertEquals("NetworkFailure: the connection failed.", reason)
    }

    // Round 25: the round-24 pins above cover ContextRejected,
    // NetworkFailure, and an AllRoads pair of PlayerRefused-login +
    // NetworkFailure. The remaining core subtypes carried the same
    // leak vectors with NO absence pin — ParseFailure's detail,
    // PlayerRefused's non-login reason wording, and ContextRejected
    // / ParseFailure appearing as nested ROADS (the recursion arm
    // was only ever exercised on two of the six subtypes) — so an
    // arm edited to interpolate its field would have restored the
    // leak while the whole suite stayed green. Every subtype's
    // arm is now pinned by marker absence, not just plain shape.
    @Test
    fun failureReasonForParseFailureNeverQuotesItsDetail() {
        val reason = downloadFailureReason(
            ParseFailure("MARKER-DETAIL https://signed.example/x?sig=SECRET"),
        )
        assertEquals(
            "ParseFailure: YouTube answered in a shape the app doesn't understand.",
            reason,
        )
        assertFalse(reason.contains("MARKER-DETAIL"))
        assertFalse(reason.contains("SECRET"))
    }

    @Test
    fun failureReasonForPlayerRefusedNeverQuotesServerWording() {
        // Non-login branch: the server's reason wording is raw
        // text and must not survive, whatever it says.
        val refused = downloadFailureReason(
            PlayerRefused("MARKER-WORDING: this video is private in your country"),
        )
        assertEquals("PlayerRefused: YouTube won't play this track.", refused)
        assertFalse(refused.contains("MARKER-WORDING"))
        // Login branch, direct (round 24 covered it only nested):
        // the token is read from the field, the wording around it
        // is still never quoted.
        val login = downloadFailureReason(
            PlayerRefused("MARKER-WORDING (LOGIN_REQUIRED)"),
        )
        assertEquals(
            "PlayerRefused: YouTube won't play this track without a sign-in.",
            login,
        )
        assertFalse(login.contains("MARKER-WORDING"))
    }

    @Test
    fun failureReasonForNestedRoadsAndStreamlessAreTypeOnly() {
        val reason = downloadFailureReason(
            AllRoadsRefused(
                ContextRejected("MARKER-ACCOUNT-TEXT"),
                ParseFailure("MARKER-ANON-DETAIL"),
            ),
        )
        assertTrue(reason.startsWith("AllRoadsRefused: "))
        assertTrue(reason.contains("YouTube refused the app's request."))
        assertTrue(
            reason.contains("YouTube answered in a shape the app doesn't understand."),
        )
        assertFalse(reason.contains("MARKER-ACCOUNT-TEXT"))
        assertFalse(reason.contains("MARKER-ANON-DETAIL"))
        assertEquals(
            "NoPlayableStream: no playable stream was served.",
            downloadFailureReason(NoPlayableStream()),
        )
    }

    @Test
    fun failureReasonForAllRoadsRefusedDescribesBothRoadsByTypeOnly() {
        val reason = downloadFailureReason(
            AllRoadsRefused(
                PlayerRefused("Sign in to confirm you're not a bot (LOGIN_REQUIRED)"),
                NetworkFailure("failed to connect to /203.0.113.7:443"),
            ),
        )
        assertTrue(reason.startsWith("AllRoadsRefused: "))
        assertTrue(reason.contains("without a sign-in"))
        assertTrue(reason.contains("the connection failed."))
        assertFalse(reason.contains("203.0.113.7"))
        assertFalse(reason.contains("not a bot"))
    }

    // -- File naming, formatting, progress -----------------------------------------

    @Test
    fun fileNamesAreSanitisedAndPartialIsDistinct() {
        assertEquals("abc-DEF_123.audio", downloadFileName("abc-DEF_123"))
        // A sanitised id carries a hash of the original, so the name
        // stays deterministic and distinct per id (see below).
        assertEquals(
            "a_b_c_d-" + "a/b:c d".hashCode().toUInt().toString(16) + ".audio",
            downloadFileName("a/b:c d"),
        )
        assertEquals("track.audio", downloadFileName(""))
        assertEquals("x.audio.part", partialFileName("x.audio"))
    }

    @Test
    fun sanitisedFileNamesNeverCollideAcrossDifferentIds() {
        // "a/b" and "a:b" sanitise to the same base — their files
        // must still be different files, or the two downloads
        // would share (and delete) one file.
        assertTrue(downloadFileName("a/b") != downloadFileName("a:b"))
        assertTrue(downloadFileName("a b") != downloadFileName("a/b"))
        assertTrue(downloadFileName("///") != downloadFileName("???"))
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

    // -- Corrupted / foreign store shapes (sweep) ------------------------------

    private fun storeFileWith(content: String): File {
        val file = File(temp.newFolder(), "downloads.json")
        file.writeText(content)
        return file
    }

    @Test
    fun truncatedStoreRecoversEmptyAndSaysSo() {
        val file = storeFileWith("""{"downloads":[{"track":{"videoId":"a","title":"T""")
        val store = DownloadStore(file)
        // No crash loop, no phantom downloads — and the loss is
        // reported on the store, not silent.
        assertEquals(emptyList<DownloadRecord>(), store.records.value)
        assertTrue(store.loadRecoveredFromCorruption)
        // The next mutation rewrites the file cleanly: recovery is
        // a one-time reset.
        store.enqueueIfAbsent(track("b"), nowMs = 1L)
        val reloaded = DownloadStore(file)
        assertEquals(listOf("b"), reloaded.records.value.map { it.videoId })
        assertFalse(reloaded.loadRecoveredFromCorruption)
    }

    @Test
    fun healthyAndMissingStoresDoNotClaimRecovery() {
        val missing = DownloadStore(File(temp.newFolder(), "downloads.json"))
        assertFalse(missing.loadRecoveredFromCorruption)
        val file = storeFileWith("""{"downloads":[]}""")
        assertFalse(DownloadStore(file).loadRecoveredFromCorruption)
    }

    @Test
    fun oldShapeAndFutureFieldsLoadHonestly() {
        // A 0.6.x-era record: no bitrate / failureReason /
        // updatedAtEpochMs (all defaulted), plus fields from a
        // NEWER build this one does not know — both must load.
        val file = storeFileWith(
            """{"downloads":[{"track":{"videoId":"old1","title":"Old","artists":["A"]},""" +
                """"state":"DONE","downloadedBytes":100,"totalBytes":100,""" +
                """"fileName":"old1.audio","futureField":42}],"brandNewTopLevel":true}""",
        )
        val store = DownloadStore(file)
        assertFalse(store.loadRecoveredFromCorruption)
        val record = store.recordFor("old1")
        assertEquals(DownloadState.DONE, record?.state)
        assertEquals(100L, record?.totalBytes)
        assertEquals("old1.audio", record?.fileName)
    }

    @Test
    fun duplicateStoredIdsLoadAsOneRecord() {
        val one = """{"track":{"videoId":"dup","title":"T"},"state":"QUEUED","fileName":"dup.audio"}"""
        val file = storeFileWith("""{"downloads":[$one,$one]}""")
        val store = DownloadStore(file)
        assertEquals(1, store.records.value.size)
        // Bug sweep round 3: the dropped duplicate is a loss like
        // any other — flagged, never silent. Before the fix the
        // dedupe drop was the one loss the corruption flag missed,
        // so the Library's corruption notice never fired for it.
        assertTrue(store.loadRecoveredFromCorruption)
    }

    @Test
    fun twoRecordsNamingTheSameFileLoadAsOneRecord() {
        // Only a tampered/foreign store can produce this (every
        // app-written name derives from its own videoId): two DONE
        // records pointing at one file would play each other's
        // audio and delete each other's download. First wins, and
        // the drop is flagged like every other salvage loss.
        val a = """{"track":{"videoId":"aaa","title":"A"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"shared.audio"}"""
        val b = """{"track":{"videoId":"bbb","title":"B"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"shared.audio"}"""
        val file = storeFileWith("""{"downloads":[$a,$b]}""")
        val store = DownloadStore(file)
        assertEquals(listOf("aaa"), store.records.value.map { it.videoId })
        assertTrue(store.loadRecoveredFromCorruption)
    }

    @Test
    fun poisonedFirstRecordLosesItsClaimedFileToTheHonestOwner() {
        // Round 4: first record in the file is the poisoned one —
        // track aaa claims the file track bbb's id derives, and
        // bbb's own clean record names it too. Plain first-wins
        // (round 3) kept aaa, dropped the genuine bbb download,
        // and aaa then played bbb's audio under aaa's name. The
        // record whose name its OWN id derives owns the file,
        // regardless of order; the poisoned record — which never
        // had a file of its own — is the one dropped, and the
        // drop is flagged like every other salvage loss.
        val poisoned = """{"track":{"videoId":"aaa","title":"A"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"bbb.audio"}"""
        val owner = """{"track":{"videoId":"bbb","title":"B"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"bbb.audio"}"""
        val file = storeFileWith("""{"downloads":[$poisoned,$owner]}""")
        val store = DownloadStore(file)
        assertEquals(listOf("bbb"), store.records.value.map { it.videoId })
        assertTrue(store.loadRecoveredFromCorruption)
    }

    @Test
    fun doneClaimantBeatsFailedOwnerForTheSharedFile() {
        // Round 5 attack on the round-4 owner rule: the honest
        // owner (bbb) is FAILED — by the discard policy a FAILED
        // record keeps no bytes, so the file on disk cannot be
        // bbb's — while the claimant (aaa) is DONE, i.e. the bytes
        // really are aaa's completed download. Round 4's owner-only
        // preference kept FAILED bbb and dropped the group's one
        // genuine completed download. DONE now beats non-DONE;
        // the honest owner still breaks ties within DONE.
        val claimant = """{"track":{"videoId":"aaa","title":"A"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"bbb.audio"}"""
        val owner = """{"track":{"videoId":"bbb","title":"B"},"state":"FAILED",""" +
            """"downloadedBytes":0,"totalBytes":10,"fileName":"bbb.audio",""" +
            """"failureReason":"DownloadFailed: named"}"""
        val file = storeFileWith("""{"downloads":[$claimant,$owner]}""")
        val store = DownloadStore(file)
        assertEquals(listOf("aaa"), store.records.value.map { it.videoId })
        assertEquals(DownloadState.DONE, store.recordFor("aaa")?.state)
        assertTrue(store.loadRecoveredFromCorruption)
    }

    @Test
    fun duplicatedIdKeepsItsHonestEntrySoTheOtherOwnerSurvives() {
        // Round 5: the same videoId appears twice — first as a
        // poisoned entry claiming bbb's file, then as its honest
        // self — alongside bbb's own record. Round 4's plain
        // distinctBy kept the poisoned first entry, the file
        // dedupe then awarded bbb.audio to bbb, and xxx was
        // dropped ENTIRELY: its genuine xxx.audio download was
        // orphaned and swept. The id dedupe now prefers the
        // honest entry, so both real downloads survive.
        val poisoned = """{"track":{"videoId":"xxx","title":"X"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"bbb.audio"}"""
        val honest = """{"track":{"videoId":"xxx","title":"X"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"xxx.audio"}"""
        val owner = """{"track":{"videoId":"bbb","title":"B"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"bbb.audio"}"""
        val file = storeFileWith("""{"downloads":[$poisoned,$honest,$owner]}""")
        val store = DownloadStore(file)
        assertEquals(
            listOf("xxx", "bbb"),
            store.records.value.map { it.videoId },
        )
        assertEquals("xxx.audio", store.recordFor("xxx")?.fileName)
        assertTrue(store.loadRecoveredFromCorruption)
    }

    @Test
    fun duplicatedIdKeepsTheDoneEntryOverTheFailedOne() {
        // Round 5: one id stored twice under its own name — a
        // FAILED entry first, the DONE entry second. First-wins
        // kept the failure and dropped the completed download
        // whose file is on disk.
        val failed = """{"track":{"videoId":"dup","title":"T"},"state":"FAILED",""" +
            """"downloadedBytes":0,"totalBytes":10,"fileName":"dup.audio",""" +
            """"failureReason":"DownloadFailed: named"}"""
        val done = """{"track":{"videoId":"dup","title":"T"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"dup.audio"}"""
        val file = storeFileWith("""{"downloads":[$failed,$done]}""")
        val store = DownloadStore(file)
        assertEquals(1, store.records.value.size)
        assertEquals(DownloadState.DONE, store.recordFor("dup")?.state)
        assertTrue(store.loadRecoveredFromCorruption)
    }

    @Test
    fun storedPartialFileNameCaseVariantsAreReplacedWithTheDerivedName() {
        // Round 4: the partial namespace is case-insensitive on
        // both sides (the store's check and the manager's sweep),
        // so a `.PART` / `.Part` finished name — which a
        // case-folding filesystem or a future sweep change could
        // treat as a deletable partial — is replaced exactly like
        // the lowercase form the round-3 test pins.
        for (variant in listOf("vid7.audio.PART", "vid7.audio.Part")) {
            val file = storeFileWith(
                """{"downloads":[{"track":{"videoId":"vid7","title":"T"},"state":"DONE",""" +
                    """"downloadedBytes":10,"totalBytes":10,"fileName":"$variant"}]}""",
            )
            val record = DownloadStore(file).recordFor("vid7")
            assertEquals(downloadFileName("vid7"), record?.fileName)
        }
    }

    @Test
    fun storedPartialFileNameIsReplacedWithTheDerivedName() {
        // A `.part` name passes the plain-name checks but lives in
        // the partial-file namespace: the download manager's
        // startup sweep deletes every `.part` file unconditionally,
        // so a DONE record stored under one would lose its own
        // file to the sweep while still claiming DONE.
        val file = storeFileWith(
            """{"downloads":[{"track":{"videoId":"vid7","title":"T"},"state":"DONE",""" +
                """"downloadedBytes":10,"totalBytes":10,"fileName":"vid7.audio.part"}]}""",
        )
        val record = DownloadStore(file).recordFor("vid7")
        assertEquals(downloadFileName("vid7"), record?.fileName)
    }

    @Test
    fun storedFileNameTraversalIsReplacedWithTheDerivedName() {
        val file = storeFileWith(
            """{"downloads":[{"track":{"videoId":"vid9","title":"T"},"state":"DONE",""" +
                """"downloadedBytes":10,"totalBytes":10,"fileName":"../../evil"}]}""",
        )
        val record = DownloadStore(file).recordFor("vid9")
        // The record can never point playback or delete outside
        // the downloads directory.
        assertEquals(downloadFileName("vid9"), record?.fileName)
    }

    @Test
    fun sequentialDedupesDoNotEraseATrackWhoseFallbackOwnsAnUncontestedFile() {
        // Round 6 (area A): the nastiest 3-record sequential case.
        // Track aaa is stored twice: first a DONE entry claiming
        // bbb's file (wins the id dedupe — DONE beats the QUEUED
        // honest entry below it), then its honest QUEUED entry
        // naming aaa.audio, which NOBODY else claims. Track bbb's
        // own DONE entry then rightly wins bbb.audio in the file
        // dedupe — but before round 6 that erased aaa ENTIRELY:
        // its id-dedupe winner was dropped by the file dedupe and
        // its fallback entry, which could have coexisted with
        // every survivor, was already gone. The loader must fall
        // back to a dropped id's next record when that record's
        // file is uncontested, instead of losing the track.
        val poisoned = """{"track":{"videoId":"aaa","title":"A"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"bbb.audio"}"""
        val honestQueued = """{"track":{"videoId":"aaa","title":"A"},"state":"QUEUED",""" +
            """"downloadedBytes":0,"fileName":"aaa.audio"}"""
        val owner = """{"track":{"videoId":"bbb","title":"B"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"bbb.audio"}"""
        val file = storeFileWith("""{"downloads":[$poisoned,$honestQueued,$owner]}""")
        val store = DownloadStore(file)
        assertEquals(
            listOf("aaa", "bbb"),
            store.records.value.map { it.videoId },
        )
        // aaa survives as its honest QUEUED self (its DONE claim
        // was to another track's bytes); bbb keeps its download.
        assertEquals(DownloadState.QUEUED, store.recordFor("aaa")?.state)
        assertEquals("aaa.audio", store.recordFor("aaa")?.fileName)
        assertEquals(DownloadState.DONE, store.recordFor("bbb")?.state)
        assertTrue(store.loadRecoveredFromCorruption)
    }

    @Test
    fun fallbackSalvageNeverStealsAFileASurvivorOwns() {
        // Round 6 (area A): the fallback in the test above must
        // only fire for an UNCONTESTED file. Here every entry for
        // aaa claims bbb.audio and nothing else, so aaa stays
        // dropped and bbb's genuine download is untouched.
        val claimant = """{"track":{"videoId":"aaa","title":"A"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"bbb.audio"}"""
        val claimantQueued = """{"track":{"videoId":"aaa","title":"A"},"state":"QUEUED",""" +
            """"downloadedBytes":0,"fileName":"bbb.audio"}"""
        val owner = """{"track":{"videoId":"bbb","title":"B"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"bbb.audio"}"""
        val file = storeFileWith("""{"downloads":[$claimant,$claimantQueued,$owner]}""")
        val store = DownloadStore(file)
        assertEquals(listOf("bbb"), store.records.value.map { it.videoId })
        assertTrue(store.loadRecoveredFromCorruption)
    }

    @Test
    fun fallbackSalvageNeverStealsALaterDroppedGroupsHonestFile() {
        // Round 7: round 6 reserved only the SURVIVORS' files
        // before the fallback walks, so dropped groups raced each
        // other in file order. Here aaa and ccc are both dropped
        // groups (their DONE winners both claim xxx's file and lose
        // it to xxx's honest DONE owner). aaa's only fallback is a
        // QUEUED claimant naming ccc.audio; ccc's fallback is its
        // honest QUEUED record naming ccc.audio. Group order walks
        // aaa first: before round 7 aaa took ccc.audio, and ccc —
        // the file's real owner, holding exactly the honest record
        // the salvage exists for — was dropped entirely. A claimant
        // fallback must never take a file another dropped group
        // owns honestly, so aaa drops and ccc survives.
        val aaaWinner = """{"track":{"videoId":"aaa","title":"A"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"xxx.audio"}"""
        val aaaClaimant = """{"track":{"videoId":"aaa","title":"A"},"state":"QUEUED",""" +
            """"downloadedBytes":0,"fileName":"ccc.audio"}"""
        val cccWinner = """{"track":{"videoId":"ccc","title":"C"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"xxx.audio"}"""
        val cccHonest = """{"track":{"videoId":"ccc","title":"C"},"state":"QUEUED",""" +
            """"downloadedBytes":0,"fileName":"ccc.audio"}"""
        val owner = """{"track":{"videoId":"xxx","title":"X"},"state":"DONE",""" +
            """"downloadedBytes":10,"totalBytes":10,"fileName":"xxx.audio"}"""
        val file = storeFileWith(
            """{"downloads":[$aaaWinner,$aaaClaimant,$cccWinner,$cccHonest,$owner]}""",
        )
        val store = DownloadStore(file)
        assertEquals(
            listOf("ccc", "xxx"),
            store.records.value.map { it.videoId },
        )
        assertEquals(DownloadState.QUEUED, store.recordFor("ccc")?.state)
        assertEquals("ccc.audio", store.recordFor("ccc")?.fileName)
        assertEquals(DownloadState.DONE, store.recordFor("xxx")?.state)
        assertTrue(store.loadRecoveredFromCorruption)
    }

    @Test
    fun storedNegativeBytesAndNamelessFailureAreSanitised() {
        val file = storeFileWith(
            """{"downloads":[{"track":{"videoId":"neg","title":"T"},"state":"FAILED",""" +
                """"downloadedBytes":-50,"totalBytes":-1,"fileName":"neg.audio"}]}""",
        )
        val record = DownloadStore(file).recordFor("neg")
        assertEquals(0L, record?.downloadedBytes)
        assertNull(record?.totalBytes)
        assertTrue(record?.failureReason?.isNotBlank() == true)
    }
}
