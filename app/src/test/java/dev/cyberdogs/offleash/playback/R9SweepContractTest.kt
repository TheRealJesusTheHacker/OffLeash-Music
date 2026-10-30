package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 9 sweep contracts (merged at integration — both round-9
 * lanes created this class independently; the finisher combined
 * them by byte-exact line-range blocks, tests unchanged):
 *
 * From lane r9a (QueueController continuation census): the four
 * listener intents round 9 found that neither cancelled an
 * in-flight autoplay continuation nor tripped its guards — the
 * onTrackEnded end-of-track sleep stop, its repeat-transition
 * twin, togglePlayPause (both branches), and previous()'s
 * seek-to-zero restarts — now cancel the continuation job at the
 * site, mirroring expireSleepTimer / retryCurrent.
 *
 * From lane r9b (fresh-eyes remainder): the PlayerScreen lyrics
 * publish is guarded on the fetched track still being the
 * controller's live current track, and the root playback-error
 * snackbar offers a Try again action routed to retryCurrent().
 *
 * Round 11 premise note (lyrics pin): the guard is
 * DEFENCE-IN-DEPTH, not load-bearing. Both lyrics fetches (LRCLIB,
 * then the client fallback) sit inside the effect's single
 * withContext(IO); when the LaunchedEffect's key changes, the
 * effect is cancelled and withContext throws
 * CancellationException at its boundary, so a stale effect's
 * post-fetch publish never executes (experiment-proven in round
 * 10 against the production coroutines version). No branch of
 * the lyrics road fetches outside that boundary. The pin below
 * therefore keeps the guard's SHAPE pinned — so a future refactor
 * that moves the fetch out of the cancellable boundary, or
 * publishes from a path cancellation cannot see, re-owes the
 * guard — without claiming the harm it would prevent can occur
 * as the code stands today.
 */
class R9SweepContractTest {

    @Test
    fun onTrackEndedSleepStopCancelsAnInFlightAutoplayContinuation() {
        // Round 12 presence guard (meta-audit, the r11a F2
        // pattern): the asserted cancel literal is widespread in
        // this file, so without the guard a missing anchor would
        // widen the slice to the whole file and pass hollow.
        assertTrue(
            "onTrackEnded must exist at all",
            controller().contains("private fun onTrackEnded()"),
        )
        val body = controller()
            .substringAfter("private fun onTrackEnded()")
            .substringBefore("private fun continueWithAutoplay")
        val sleepBranch = body
            .substringAfter("sleepTimerShouldStopAtTrackEnd")
            .substringBefore("return")
        assertTrue(
            "onTrackEnded's sleep stop must cancel autoplayJob — a " +
                "continuation started by a manual Next is not " +
                "sleep-guarded and would play past the stop",
            sleepBranch.contains("autoplayJob?.cancel()"),
        )
    }


    @Test
    fun repeatTransitionSleepStopCancelsAnInFlightAutoplayContinuation() {
        assertTrue(
            "the media-item transition hook must exist at all (round 12 guard)",
            controller().contains("onMediaItemTransition"),
        )
        val hook = controller()
            .substringAfter("onMediaItemTransition")
            .substringBefore("override fun onPlayerError")
        assertTrue(
            "the repeat-transition sleep stop is onTrackEnded's twin " +
                "and must cancel autoplayJob the same way",
            hook.contains("autoplayJob?.cancel()"),
        )
    }


    @Test
    fun previousCancelsAnInFlightAutoplayContinuation() {
        assertTrue(
            "previous() must exist at all (round 12 guard)",
            controller().contains("fun previous()"),
        )
        val body = controller()
            .substringAfter("fun previous()")
            .substringBefore("fun seekTo(")
        assertTrue(
            "previous() must cancel autoplayJob — its seek-to-zero " +
                "branches trip neither continuation guard (identity " +
                "and index both stand)",
            body.contains("autoplayJob?.cancel()"),
        )
        // Placement is part of the claim (round 10 strengthening,
        // the round-8 epoch-pin precedent): the cancel must sit
        // BEFORE the seek-to-zero early return — a cancel placed
        // after it would leave exactly the restart branch this pin
        // exists for unprotected while the pin stayed green.
        assertTrue(
            "previous()'s cancel must precede its first seek-to-zero " +
                "early return, or that branch never executes it",
            body.indexOf("autoplayJob?.cancel()") < body.indexOf("player.seekTo(0)"),
        )
    }


    @Test
    fun togglePlayPauseCancelsAnInFlightAutoplayContinuation() {
        // Round 12 strengthening (meta-audit): round 11 inserted
        // stopPlayback between pausePlayback and next(), so this
        // slice (toggle -> next) gained stop's cancel as a third
        // hit — the count below would have stayed >= 2 with
        // pausePlayback's own cancel deleted, i.e. hollow for
        // exactly the pause-branch claim it names. Bounding the
        // slice before stopPlayback restores the round-11
        // measurement: exactly 2 cancels (the ENDED/IDLE branch's
        // own + pausePlayback's, via the delegation).
        val body = controller()
            .substringAfter("fun togglePlayPause()")
            .substringBefore("fun next()")
            .substringBefore("fun stopPlayback()")
        val cancels = Regex("autoplayJob\\?\\.cancel\\(\\)").findAll(body).count()
        assertTrue(
            "togglePlayPause must cancel autoplayJob on BOTH the pause " +
                "branch and the ENDED/IDLE re-resolve branch (found " +
                "$cancels) — since round 10 the pause branch delegates " +
                "to pausePlayback(), whose body sits inside this slice " +
                "and carries the pause side's cancel; the delegation " +
                "itself is pinned by playback/R10SweepContractTest",
            cancels >= 2,
        )
    }


    @Test
    fun lyricsPublishIsGuardedOnTheFetchedTrackStillBeingCurrent() {
        val player = mainSource("java/dev/cyberdogs/offleash/ui/player/PlayerScreen.kt")
        val effect = player
            .substringAfter("LaunchedEffect(currentTrack?.videoId)")
            .substringBefore("Surface(")
        assertTrue(
            "the lyrics publish must be guarded on the live queue's " +
                "current track still being the fetched track",
            effect.contains(
                "container.queueController.queueState.value",
            ) &&
                effect.contains(".currentTrack?.videoId == track.videoId"),
        )
        assertTrue(
            "the guarded publish must sit after the fetch's cache put " +
                "(the publish is the guarded half, not the cache)",
            effect.indexOf("playerLyricsCache.put(track.videoId, result)") >= 0 &&
                effect.indexOf(".currentTrack?.videoId == track.videoId") >
                effect.indexOf("playerLyricsCache.put(track.videoId, result)"),
        )
        assertTrue(
            "the publish itself must be inside the guard — an " +
                "unconditional assignment after it would leave the " +
                "panel publish unguarded for any future path where " +
                "a stale result can reach it (today the fetch's " +
                "cancellation boundary already prevents that; see " +
                "the class doc's round-11 premise note)",
            effect
                .substringAfter(".currentTrack?.videoId == track.videoId")
                .contains("lyricsState = result.toLyricsUiState()"),
        )
    }


    @Test
    fun playbackErrorSnackbarOffersTryAgainAndRetries() {
        val root = mainSource("java/dev/cyberdogs/offleash/ui/OffleashRoot.kt")
        val effect = root
            .substringAfter("LaunchedEffect(namedError, playerExpanded)")
            .substringBefore("val openAlbum")
        assertTrue(
            "the playback-error snackbar must offer the Try again action",
            effect.contains("actionLabel = \"Try again\""),
        )
        assertTrue(
            "performing the action must retry the current track " +
                "through the same entry the player's error card uses",
            effect.contains("SnackbarResult.ActionPerformed") &&
                effect.contains("container.queueController.retryCurrent()"),
        )
        assertTrue(
            "the retry must be driven by the snackbar's result, not " +
                "fire unconditionally alongside the show",
            effect.indexOf("retryCurrent()") >
                effect.indexOf("SnackbarResult.ActionPerformed"),
        )
    }


    private fun controller(): String =
        mainSource("java/dev/cyberdogs/offleash/playback/QueueController.kt")


    /** Reads a file under app/src/main, located like the other contract tests. */
    private fun mainSource(relativePath: String): String {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, "src/main/$relativePath")
            candidates += File(dir, "app/src/main/$relativePath")
            dir = dir.parentFile
            hops++
        }
        val found = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "$relativePath not found from " + File(".").absolutePath +
                    " (tried: " + candidates.joinToString { it.path } + ")",
            )
        return found.readText()
    }


}
