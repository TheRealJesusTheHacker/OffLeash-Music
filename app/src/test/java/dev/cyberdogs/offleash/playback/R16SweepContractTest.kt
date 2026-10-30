package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 16 sweep contracts (confirmation round — adjudicating
 * round 15's observations):
 *
 * Round 15's first observation was a finding, not an observation.
 * The resolving-flag census of rounds 13–15 covered WHO clears
 * the flag, but two end-of-track sleep-stop branches never staled
 * the third fetch: a radio fetch in flight when the stop fired
 * kept its epoch, landed after the pause, replaced the queue,
 * and PLAYED — defeating the sleep stop exactly as the
 * continuation once could before round 9's census fixed it at
 * its own branches. Round 9 fixed the continuation at every
 * sleep stop; the radio is the other in-flight fetch, and two
 * sleep-stop branches did not reach it:
 *
 * 1. continueWithAutoplay's fromTrackEnd sleep branch — its
 *    timed twin routes through expireSleepTimer, which bumps
 *    queueEpoch; this branch paused and cleared but bumped
 *    nothing. Construction: queue exhausts (continuation starts),
 *    listener taps Start radio (radio fetch starts), listener
 *    arms the end-of-track timer (all during both fetches), the
 *    continuation lands first and takes this stop branch, then
 *    the radio lands with its epoch intact and plays.
 *
 * 2. The repeat-transition sleep stop (onMediaItemTransition,
 *    REPEAT reason) — the same construction without any
 *    continuation: radio fetch in flight under Repeat-One, the
 *    listener arms the end-of-track timer, the track loops, the
 *    stop pauses, the radio lands and plays. onTrackEnded's own
 *    sleep branch needs no fix: it is preceded by that
 *    function's entry bump, and stopPlayback/expireSleepTimer
 *    bump too — these two were the census's remainder.
 *
 * Fix at both sites: queueEpoch += 1 in the stop branch (the
 * epoch is the established handle on a radio landing; the stale
 * landing then releases or defers the flag in its epoch branch
 * and never plays).
 *
 * 3. Doc fix: expireSleepTimer's comment still claimed "the
 *    radio job is not held in a field" — false since round 14
 *    held it in radioJob. The comment now states the epoch bump
 *    is the handle used there by choice; the bump itself (and
 *    round 7's pin on it) is unchanged.
 *
 * QueueController is Media3-bound, so the shape is the pin
 * (house style; CI is the compile gate).
 */
class R16SweepContractTest {

    @Test
    fun continuationEndOfTrackSleepStopStalesARadioLanding() {
        assertTrue(
            "the continuation's end-of-track sleep branch must exist at all",
            controller().contains("if (fromTrackEnd &&"),
        )
        val branch = controller()
            .substringAfter("if (fromTrackEnd &&")
            .substringBefore("val timer = _sleepTimerState.value")
        assertTrue(
            "the continuation's end-of-track sleep stop must bump " +
                "queueEpoch — a radio fetch started mid-continuation " +
                "otherwise keeps its epoch, lands after this pause, " +
                "replaces the queue, and plays, defeating the stop " +
                "(its timed twin is covered by expireSleepTimer's bump)",
            branch.contains("queueEpoch += 1") &&
                branch.contains("cancelSleepTimer()") &&
                branch.contains("setResolving(false)"),
        )
        // Round 17 strengthening: the slice runs past the branch's
        // closing brace (it ends at the timed branch's timer read),
        // so presence alone would also pass with the bump moved to
        // just AFTER the branch — where it would fire on every
        // continuation landing, not just the sleep stop. The bump
        // must precede the branch's own next statement.
        assertTrue(
            "the bump must sit inside the sleep branch, before its " +
                "cancelSleepTimer() — a bump after the branch's close " +
                "is a different (unconditional) bump, not this stop's",
            branch.indexOf("queueEpoch += 1") <
                branch.indexOf("cancelSleepTimer()"),
        )
    }

    @Test
    fun repeatTransitionSleepStopStalesARadioLanding() {
        assertTrue(
            "the repeat-transition sleep stop must exist at all",
            controller().contains("MEDIA_ITEM_TRANSITION_REASON_REPEAT"),
        )
        val branch = controller()
            .substringAfter("MEDIA_ITEM_TRANSITION_REASON_REPEAT")
            .substringBefore("override fun onPlayerError")
        assertTrue(
            "the repeat-transition sleep stop must bump queueEpoch — " +
                "it is not onTrackEnded, so no entry bump covers it, " +
                "and a radio fetch in flight otherwise lands after " +
                "this pause and plays, defeating the end-of-track stop",
            branch.contains("queueEpoch += 1") &&
                branch.contains("autoplayJob?.cancel()") &&
                branch.contains("clearResolvingIfOrphaned"),
        )
        // Round 17 strengthening: the slice runs to the next
        // override, past the branch's closing brace, so presence
        // alone would also pass with the bump moved to just AFTER
        // the branch — where it would fire on every repeat
        // transition, sleep timer or not. Inside the branch the
        // bump sits between the continuation cancel and the
        // orphan-clear helper; pin that placement.
        assertTrue(
            "the bump must sit inside the sleep branch, between " +
                "the continuation cancel and the orphan-clear helper",
            branch.indexOf("autoplayJob?.cancel()") <
                branch.indexOf("queueEpoch += 1") &&
                branch.indexOf("queueEpoch += 1") <
                branch.indexOf("clearResolvingIfOrphaned"),
        )
    }

    @Test
    fun expireSleepTimerCommentNoLongerClaimsTheRadioJobIsUnheld() {
        assertTrue(
            "expireSleepTimer must exist at all",
            controller().contains("private fun expireSleepTimer()"),
        )
        val body = controller()
            .substringAfter("private fun expireSleepTimer()")
            .substringBefore("fun setPlaybackSpeed")
        assertTrue(
            "expireSleepTimer's epoch bump must remain — round 7's " +
                "discipline is unchanged by the comment correction",
            body.contains("queueEpoch += 1"),
        )
        assertFalse(
            "expireSleepTimer's comment must not claim the radio job " +
                "is not held in a field — radioJob has held it since " +
                "round 14; the epoch is the handle used there by choice",
            body.contains("not held in a field"),
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
