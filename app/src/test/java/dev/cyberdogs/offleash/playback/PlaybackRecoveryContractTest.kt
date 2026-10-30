package dev.cyberdogs.offleash.playback

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract for the H7 stream-recovery wiring: the decisions
 * in PlaybackRecovery.kt only matter if QueueController actually
 * routes player errors through them, resumes at the captured
 * position, and the error card's Try again re-resolves from
 * scratch. The controller needs a device to run, but the wiring is
 * pinnable here, in the house contract-test style.
 */
class PlaybackRecoveryContractTest {

    private fun source(relativeFromAppModule: String): String {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, relativeFromAppModule)
            candidates += File(dir, "app/$relativeFromAppModule")
            dir = dir.parentFile
            hops++
        }
        val hit = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "$relativeFromAppModule not found from ${File(".").absolutePath}",
            )
        return hit.readText()
    }

    private fun queueController(): String =
        source("src/main/java/dev/cyberdogs/offleash/playback/QueueController.kt")

    private fun onPlayerErrorBody(): String =
        queueController()
            .substringAfter("override fun onPlayerError")
            .substringBefore("_namedError.value = ErrorMessages.playerMessage")

    @Test
    fun playerErrorsAreDecidedByTheRecoveryDecision() {
        val body = onPlayerErrorBody()
        assertTrue(body.contains("playerErrorRecoveryDecision("))
        assertTrue(body.contains("PlayerErrorRecoveryAction.RE_RESOLVE_IO"))
        assertTrue(body.contains("PlayerErrorRecoveryAction.RE_RESOLVE_REFUSED"))
        assertTrue(body.contains("PlayerErrorRecoveryAction.FALLBACK_TO_STREAM"))
        assertTrue(body.contains("PlayerErrorRecoveryAction.SKIP_TO_NEXT"))
        assertTrue(body.contains("PlayerErrorRecoveryAction.SURFACE_ERROR"))
    }

    @Test
    fun recoveryResumesAtThePositionCapturedAtFailure() {
        val body = onPlayerErrorBody()
        assertTrue(
            "the failure position must be captured from the player",
            body.contains("player.currentPosition"),
        )
        assertTrue(
            "recovery re-resolves must carry the resume position",
            body.contains("recoverySeekMs = failedPositionMs"),
        )
        val handOff = queueController().substringAfter("private fun handToPlayer")
        assertTrue(
            "the hand-off must lodge the seek before prepare",
            handOff.contains("player.seekTo(seekToMs)"),
        )
        assertTrue(
            handOff.indexOf("player.seekTo(seekToMs)") <
                handOff.indexOf("player.prepare()"),
        )
    }

    @Test
    fun recoverySpendsItsOwnBudgetNotTheReadyResetOne() {
        val source = queueController()
        assertTrue(source.contains("streamRecoveryAttempts"))
        // NEITHER recovery budget is reset on READY (bug sweep
        // round 3): the IO counter never was (the H7 failure
        // happens after READY, mid-stream), and refunding only the
        // shipped 403 flag there composed into an unbounded loop —
        // a stream dying mid-stream with 403s re-entered the fast
        // path after every READY, never spent the IO budget, and
        // never reached the skip. Both budgets now live for one
        // track play and reset on a fresh play / queue-position
        // change (the resets in resolveAndPlay/syncQueueState).
        val readyBlock = source
            .substringAfter("playbackState == Player.STATE_READY")
            .substringBefore("Player.STATE_ENDED")
        assertFalse(
            "STATE_READY must not refund the IO recovery budget",
            readyBlock.contains("streamRecoveryAttempts = 0"),
        )
        assertFalse(
            "STATE_READY must not refund the one-shot 403 budget either",
            readyBlock.contains("retriedCurrentTrack = false"),
        )
        // The budgets DO still reset where a new track play begins.
        assertTrue(
            "a fresh (non-retry) play must reset both budgets",
            source.contains("streamRecoveryAttempts = 0") &&
                source.contains("retriedCurrentTrack = false"),
        )
        // Round 7 strengthening: the whole-file contains() above is
        // satisfied by EITHER reset site, so deleting the reset from
        // one site left this test green while that site's plays kept
        // spent budgets (the SyncFreshness round-6 disease, one file
        // over). Pin each site in its own slice: a fresh play in
        // resolveAndPlay, and a queue-position change in
        // syncQueueState, must EACH reset both budgets.
        // Round 12 presence guards (meta-audit): each reset
        // literal lives at BOTH sites, so the syncQueueState
        // slice in particular would pass hollow on a widened
        // slice over resolveAndPlay's resets if its anchor
        // vanished.
        assertTrue("resolveAndPlay must exist at all", source.contains("private fun resolveAndPlay"))
        assertTrue("syncQueueState must exist at all", source.contains("private fun syncQueueState()"))
        val resolve = source
            .substringAfter("private fun resolveAndPlay")
            .substringBefore("private fun handToPlayer")
        assertTrue(
            "resolveAndPlay's fresh-play branch must reset the IO budget",
            resolve.contains("streamRecoveryAttempts = 0"),
        )
        assertTrue(
            "resolveAndPlay's fresh-play branch must reset the 403 budget",
            resolve.contains("retriedCurrentTrack = false"),
        )
        val sync = source
            .substringAfter("private fun syncQueueState()")
            .substringBefore("private fun syncPlaybackState()")
        assertTrue(
            "syncQueueState's position change must reset the IO budget",
            sync.contains("streamRecoveryAttempts = 0"),
        )
        assertTrue(
            "syncQueueState's position change must reset the 403 budget",
            sync.contains("retriedCurrentTrack = false"),
        )
    }

    @Test
    fun localFallbackSkipsTheDownloadLookup() {
        val body = queueController()
            .substringAfter("private fun resolveAndPlay")
            .substringBefore("private fun handToPlayer")
        assertTrue(body.contains("forceStream"))
        assertTrue(
            "a forced-stream resolve must not consult the local provider first",
            body.contains("if (forceStream)"),
        )
    }

    @Test
    fun errorCardTryAgainReResolvesFromScratch() {
        val controller = queueController()
        val retry = controller.substringAfter("fun retryCurrent()")
            .substringBefore("fun ")
        assertTrue(
            "retryCurrent must go through the full resolve path",
            retry.contains("resolveAndPlay(track)"),
        )
        assertFalse(
            "no player-level retry of the dead item anywhere in the controller",
            controller.contains(".retry()"),
        )
        val playerScreen = source(
            "src/main/java/dev/cyberdogs/offleash/ui/player/PlayerScreen.kt",
        )
        assertTrue(
            "the error card must offer Try again wired to retryCurrent",
            playerScreen.contains("container.queueController.retryCurrent()"),
        )
        assertTrue(playerScreen.contains("\"Try again\""))
    }

    @Test
    fun endOfTrackSleepTimerAlsoFiresUnderRepeatOne() {
        // Repeat-one loops inside the player and STATE_ENDED never
        // fires, so the end-of-track sleep timer needs the repeat
        // transition hook or it can never stop a looping track.
        val source = queueController()
        assertTrue(source.contains("MEDIA_ITEM_TRANSITION_REASON_REPEAT"))
        val hook = source.substringAfter("MEDIA_ITEM_TRANSITION_REASON_REPEAT")
            .substringBefore("override fun onPlayerError")
        assertTrue(hook.contains("sleepTimerShouldStopAtTrackEnd"))
        assertTrue(hook.contains("player.pause()"))
    }

    @Test
    fun supersededResolveDoesNotClearTheResolvingFlag() {
        // A cancelled resolve used to clear isResolving
        // unconditionally, leaving a successor's whole resolve with
        // no loading indicator. The clear is now ownership-checked.
        val source = queueController()
        assertTrue(
            source.contains("resolveJob === currentCoroutineContext()[Job]"),
        )
    }

    @Test
    fun playButtonAfterThePlayerEndedReResolves() {
        // In STATE_ENDED the player ignores play(): the toggle must
        // re-resolve the current track instead of looking alive and
        // doing nothing.
        val toggle = queueController()
            .substringAfter("fun togglePlayPause()")
            .substringBefore("fun next()")
        assertTrue(toggle.contains("Player.STATE_ENDED"))
        assertTrue(toggle.contains("resolveAndPlay(it)"))
    }

    @Test
    fun playButtonAfterAPlayerErrorReResolvesToo() {
        // Round 4: STATE_ENDED was only half the dead-play class.
        // After a player error the player sits in STATE_IDLE still
        // holding the failed item, and play() on an unprepared
        // player does nothing — the toggle's media-item branch
        // would have called exactly that. IDLE must take the same
        // re-resolve branch as ENDED.
        val toggle = queueController()
            .substringAfter("fun togglePlayPause()")
            .substringBefore("fun next()")
        assertTrue(toggle.contains("Player.STATE_IDLE"))
        assertTrue(toggle.contains("resolveAndPlay(it)"))
    }

    @Test
    fun radioStartCoversItsFetchWithTheLoadingState() {
        val radio = queueController()
            .substringAfter("fun startRadio(track: Track)")
            .substringBefore("private fun onTrackEnded()")
        assertTrue(radio.contains("setResolving(true)"))
        assertTrue(radio.contains("setResolving(false)"))
    }

    @Test
    fun recoveryAttemptsReachDiagnosticsWithoutUrls() {
        val body = onPlayerErrorBody()
        // Every recovery action logs a stage line; none of the
        // recovery wiring may put a stream URL into diagnostics.
        assertTrue(body.contains("\"recovery\""))
        assertFalse(body.contains("stream.info.url"))
        assertFalse(body.contains("info.url"))
    }
}
