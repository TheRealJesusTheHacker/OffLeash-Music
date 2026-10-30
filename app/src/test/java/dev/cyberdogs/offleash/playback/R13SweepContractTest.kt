package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 13 sweep contracts (confirmation round — the flag-orphan
 * census, breaking round 12's stop fix, and the transport's
 * repeat/shuffle verbs):
 *
 * 1. Round 12 fixed the resolving flag's orphan at stopPlayback:
 * a cancelled autoplay continuation reaches no branch that clears
 * the flag, so the cancelling site must clear it. The census of
 * EVERY site that cancels the continuation job found the same
 * orphan at five more sites (pause, previous()'s seek-to-zero
 * exits, sleep-timer expiry, and both end-of-track sleep stops)
 * plus the out-of-range exit of playQueueItem. Those sites clear
 * through one helper that respects the flag's other owners: it
 * acts only when a continuation really was in flight at the
 * cancel, and never while a live resolve owns the flag. Expiry,
 * which cancels both jobs and bumps the epoch like a stop,
 * clears unconditionally in the stop's own shape.
 *
 * 2. The transport player never overrode the repeat/shuffle
 * verbs, so they delegated to the raw player: a controller-set
 * repeat looped the player's single item while the queue and the
 * UI said OFF — the track end the controller advances on never
 * fired and the queue stalled — and a player-level shuffle flag
 * diverged from the queue's own shuffle order. The verbs are
 * reachable in the shipped media3-session 1.5.1: the session
 * advertises the repeat/shuffle commands to every connected
 * controller, and the platform/legacy stub converts Bluetooth
 * (AVRCP), Wear and system controllers' repeat/shuffle calls
 * into exactly these methods. Repeat and shuffle are queue
 * state, so both verbs now route through the controller's own
 * setters and the getters report the queue's state.
 *
 * QueueController and PlaybackService are Media3-bound, so the
 * shape is the pin (house style; CI is the compile gate).
 */
class R13SweepContractTest {

    // -- 1. The flag-orphan census --------------------------------

    @Test
    fun orphanClearHelperClearsOnlyForACancelledContinuationWithNoLiveResolve() {
        assertTrue(
            "the orphan-clear helper must exist at all",
            controller().contains("private fun clearResolvingIfOrphaned"),
        )
        val body = controller()
            .substringAfter("private fun clearResolvingIfOrphaned")
            .substringBefore("private fun setResolving")
        assertTrue(
            "the helper must clear only when a continuation was in " +
                "flight at the cancel and no live resolve owns the flag",
            body.contains("continuationWasInFlight &&") &&
                body.contains("resolveJob?.isActive != true"),
        )
        assertTrue(
            "the helper must also respect a live radio fetch's " +
                "ownership of the flag (round 14: a radio fetch is " +
                "a third owner, and a continuation and a radio " +
                "fetch can be in flight at the same time)",
            body.contains("radioJob?.isActive != true"),
        )
        assertTrue(
            "the helper must clear the resolving flag under its guard",
            body.contains("setResolving(false)"),
        )
    }

    @Test
    fun pausePlaybackClearsTheResolvingFlagItsCancelOrphans() {
        assertTrue(
            "pausePlayback must exist at all",
            controller().contains("fun pausePlayback()"),
        )
        val body = controller()
            .substringAfter("fun pausePlayback()")
            .substringBefore("fun stopPlayback()")
        assertTrue(
            "pausePlayback must capture whether a continuation was " +
                "in flight before it cancels the job",
            body.contains("autoplayJob?.isActive"),
        )
        assertTrue(
            "pausePlayback must clear an orphaned resolving flag " +
                "after its cancel — a paused player reaches no READY " +
                "whose listener would clear it",
            body.indexOf("autoplayJob?.cancel()") <
                body.indexOf("clearResolvingIfOrphaned(continuationWasInFlight)"),
        )
    }

    @Test
    fun previousClearsTheResolvingFlagBeforeItsSeekToZeroExits() {
        assertTrue(
            "previous() must exist at all",
            controller().contains("fun previous()"),
        )
        val body = controller()
            .substringAfter("fun previous()")
            .substringBefore("fun seekTo(")
        assertTrue(
            "previous() must clear an orphaned resolving flag after " +
                "its cancel and before its seek-to-zero exits, which " +
                "start no resolve to re-own the flag",
            body.indexOf("autoplayJob?.cancel()") <
                body.indexOf("clearResolvingIfOrphaned(continuationWasInFlight)") &&
                body.indexOf("clearResolvingIfOrphaned(continuationWasInFlight)") <
                body.indexOf("player.seekTo(0)"),
        )
    }

    @Test
    fun expireSleepTimerClearsTheResolvingFlagLikeAStop() {
        assertTrue(
            "expireSleepTimer must exist at all",
            controller().contains("private fun expireSleepTimer()"),
        )
        val body = controller()
            .substringAfter("private fun expireSleepTimer()")
            .substringBefore("fun setPlaybackSpeed")
        assertTrue(
            "expireSleepTimer cancels both in-flight jobs and bumps " +
                "the epoch, so like a stop it must clear the resolving " +
                "flag itself, after its cancels",
            body.indexOf("resolveJob?.cancel()") <
                body.indexOf("setResolving(false)"),
        )
    }

    @Test
    fun onTrackEndedSleepStopClearsTheResolvingFlagItsCancelOrphans() {
        assertTrue(
            "onTrackEnded must exist at all",
            controller().contains("private fun onTrackEnded()"),
        )
        val body = controller()
            .substringAfter("private fun onTrackEnded()")
            .substringBefore("private fun continueWithAutoplay")
        assertTrue(
            "onTrackEnded's sleep stop must clear an orphaned " +
                "resolving flag after its cancel, inside the branch " +
                "that pauses instead of advancing",
            body.indexOf("autoplayJob?.cancel()") <
                body.indexOf("clearResolvingIfOrphaned(continuationWasInFlight)") &&
                body.indexOf("clearResolvingIfOrphaned(continuationWasInFlight)") <
                body.indexOf("player.pause()"),
        )
    }

    @Test
    fun repeatTransitionSleepStopClearsTheResolvingFlagItsCancelOrphans() {
        assertTrue(
            "the media-item transition hook must exist at all",
            controller().contains("onMediaItemTransition"),
        )
        val hook = controller()
            .substringAfter("onMediaItemTransition")
            .substringBefore("override fun onPlayerError")
        assertTrue(
            "the repeat-transition sleep stop is onTrackEnded's " +
                "twin and must clear an orphaned resolving flag " +
                "after its cancel the same way",
            hook.indexOf("autoplayJob?.cancel()") <
                hook.indexOf("clearResolvingIfOrphaned(continuationWasInFlight)"),
        )
    }

    @Test
    fun playQueueItemOutOfRangeClearsTheResolvingFlagItsChainResetOrphans() {
        assertTrue(
            "playQueueItem must exist at all",
            controller().contains("fun playQueueItem(index: Int)"),
        )
        val body = controller()
            .substringAfter("fun playQueueItem(index: Int)")
            .substringBefore("fun addToQueue")
        assertTrue(
            "playQueueItem's out-of-range exit must clear an " +
                "orphaned resolving flag — the chain reset cancelled " +
                "the continuation and the exit starts no resolve",
            body.contains("if (track == null)") &&
                body.indexOf("queue.playIndex(index)") <
                body.indexOf("clearResolvingIfOrphaned(continuationWasInFlight)"),
        )
    }

    @Test
    fun startRadioHoldsItsFetchInAFieldTheOrphanGuardCanSee() {
        assertTrue(
            "the radio job must be held in a field at all (round " +
                "14: an unrecorded launch is invisible to the " +
                "orphan-clear helper's ownership guard)",
            controller().contains("private var radioJob: Job? = null"),
        )
        val body = controller()
            .substringAfter("fun startRadio(track: Track)")
            .substringBefore("private fun onTrackEnded()")
        assertTrue(
            "startRadio must record its fetch in the radio job " +
                "field, not launch it unrecorded",
            body.contains("radioJob = scope.launch"),
        )
    }

    @Test
    fun orphanClearCapturesAreTakenBeforeTheCancelsTheyGuard() {
        // A capture read AFTER its site's cancel (and null) can
        // only ever read false — the helper would never clear and
        // the orphan would persist under a fixed-looking shape
        // (round 14). Every guarded site must capture the
        // continuation's liveness BEFORE the cancel it guards;
        // the per-site pins above assert the helper call's
        // placement but not the capture's, so it is pinned here
        // for all five sites at once.
        val capture = "val continuationWasInFlight = autoplayJob?.isActive == true"
        val pause = controller()
            .substringAfter("fun pausePlayback()")
            .substringBefore("fun stopPlayback()")
        assertTrue(
            "pausePlayback must capture before it cancels",
            pause.indexOf(capture) >= 0 &&
                pause.indexOf(capture) < pause.indexOf("autoplayJob?.cancel()"),
        )
        val previous = controller()
            .substringAfter("fun previous()")
            .substringBefore("fun seekTo(")
        assertTrue(
            "previous() must capture before it cancels",
            previous.indexOf(capture) >= 0 &&
                previous.indexOf(capture) < previous.indexOf("autoplayJob?.cancel()"),
        )
        val hook = controller()
            .substringAfter("onMediaItemTransition")
            .substringBefore("override fun onPlayerError")
        assertTrue(
            "the repeat-transition sleep stop must capture before it cancels",
            hook.indexOf(capture) >= 0 &&
                hook.indexOf(capture) < hook.indexOf("autoplayJob?.cancel()"),
        )
        val ended = controller()
            .substringAfter("private fun onTrackEnded()")
            .substringBefore("private fun continueWithAutoplay")
        assertTrue(
            "onTrackEnded's sleep stop must capture before it cancels",
            ended.indexOf(capture) >= 0 &&
                ended.indexOf(capture) < ended.indexOf("autoplayJob?.cancel()"),
        )
        val item = controller()
            .substringAfter("fun playQueueItem(index: Int)")
            .substringBefore("fun addToQueue")
        assertTrue(
            "playQueueItem must capture before the chain reset " +
                "cancels the continuation, and its clear must sit " +
                "inside the out-of-range branch it serves",
            item.indexOf(capture) >= 0 &&
                item.indexOf(capture) < item.indexOf("resetAutoplayChain()") &&
                item.indexOf("if (track == null)") <
                item.indexOf("clearResolvingIfOrphaned(continuationWasInFlight)") &&
                item.indexOf("clearResolvingIfOrphaned(continuationWasInFlight)") <
                item.indexOf("resolveAndPlay(track)"),
        )
    }

    // -- 2. Repeat/shuffle are queue state -------------------------

    @Test
    fun controllerSetRepeatModeWritesQueueAndPlayerTogether() {
        assertTrue(
            "the controller must have a repeat setter at all",
            controller().contains("fun setRepeatMode(mode: RepeatMode)"),
        )
        val body = controller()
            .substringAfter("fun setRepeatMode(mode: RepeatMode)")
            .substringBefore("fun playQueueItem")
        assertTrue(
            "the repeat setter must write the queue's mode",
            body.contains("queue.repeatMode = mode"),
        )
        assertTrue(
            "the repeat setter must write the player's mode in the " +
                "same step — repeat-one is the player's loop, every " +
                "other mode leaves the player off so track ends fire",
            body.contains("player.repeatMode =") &&
                body.contains("RepeatMode.ONE"),
        )
        assertTrue(
            "the repeat setter must republish the queue state",
            body.contains("syncQueueState()"),
        )
        assertTrue(
            "the in-app cycle must delegate to the one repeat " +
                "write, not keep a second direct write of its own",
            body.contains("setRepeatMode(nextMode)") &&
                !body.contains("queue.repeatMode = nextMode"),
        )
    }

    @Test
    fun sessionPlayerRepeatVerbRoutesToTheController() {
        val service = service()
        assertTrue(
            "the session player must override the repeat verb at all",
            service.contains("override fun setRepeatMode"),
        )
        val verb = service
            .substringAfter("override fun setRepeatMode")
            .substringBefore("override fun getRepeatMode")
        assertTrue(
            "the repeat verb must route to the controller's repeat " +
                "write with the modes mapped, not delegate to the raw " +
                "player (whose loop would stall the queue silently)",
            verb.contains("queueController.setRepeatMode(") &&
                verb.contains("Player.REPEAT_MODE_ONE -> RepeatMode.ONE") &&
                verb.contains("Player.REPEAT_MODE_ALL -> RepeatMode.ALL") &&
                !verb.contains("super.setRepeatMode"),
        )
        assertTrue(
            "the session player must override the repeat getter " +
                "at all (round 14 guard: without it the slice below " +
                "widens to the file prefix and is deletion-sensitive " +
                "only by the fallback's reversed mapping spellings)",
            service.contains("override fun getRepeatMode"),
        )
        val getter = service
            .substringAfter("override fun getRepeatMode")
            .substringBefore("override fun setShuffleModeEnabled")
        assertTrue(
            "the repeat getter must report the queue's published " +
                "mode, including ALL (the queue's wrap), which the " +
                "raw player never holds",
            getter.contains("queueController.queueState.value.repeatMode") &&
                getter.contains("RepeatMode.ALL -> Player.REPEAT_MODE_ALL"),
        )
    }

    @Test
    fun sessionPlayerShuffleVerbRoutesToTheController() {
        val service = service()
        assertTrue(
            "the session player must override the shuffle verb at all",
            service.contains("override fun setShuffleModeEnabled"),
        )
        val verb = service
            .substringAfter("override fun setShuffleModeEnabled")
            .substringBefore("override fun getShuffleModeEnabled")
        assertTrue(
            "the shuffle verb must route to the controller's " +
                "shuffle write — the shuffle order lives in the " +
                "queue, and a player-level flag on a one-item " +
                "playlist could only diverge from it",
            verb.contains("queueController.setShuffle(shuffleModeEnabled)") &&
                !verb.contains("super.setShuffleModeEnabled"),
        )
        assertTrue(
            "the session player must override the shuffle getter " +
                "at all (round 14 guard: without it the slice below " +
                "widens to the whole file and is deletion-sensitive " +
                "only because the reported literal occurs nowhere else)",
            service.contains("override fun getShuffleModeEnabled"),
        )
        val getter = service
            .substringAfter("override fun getShuffleModeEnabled")
        assertTrue(
            "the shuffle getter must report the queue's published " +
                "shuffle state",
            getter.contains("queueController.queueState.value.isShuffled"),
        )
    }

    private fun controller(): String =
        mainSource("java/dev/cyberdogs/offleash/playback/QueueController.kt")

    private fun service(): String =
        mainSource("java/dev/cyberdogs/offleash/playback/PlaybackService.kt")

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
