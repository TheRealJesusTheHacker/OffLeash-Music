package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 17 sweep contracts (confirmation round — breaking round
 * 16's fixes):
 *
 * Round 16 staled the RADIO at the two end-of-track sleep-stop
 * branches that lacked an epoch bump. Its census asked which
 * fetches the bump reaches — and the bump reaches only the radio,
 * because the radio is the only fetch that captures the epoch.
 * The third in-flight fetch, a RESOLVE, captures no epoch at all:
 * its landing hands a track to the player and presses play with
 * no epoch or sleep check. The timed expiry and the transport
 * stop both cancel the resolve job at their sites for exactly
 * that reason; the three end-of-track sleep branches (the
 * continuation's, onTrackEnded's, and the repeat transition's)
 * cancelled the continuation and (after round 16) staled the
 * radio, but never cancelled a resolve — so a resolve in flight
 * when an end-of-track stop fired landed after the pause and
 * PLAYED, defeating the stop.
 *
 * Constructions (one per pin):
 * 1. Continuation branch: a Next tap's resolve is still in
 *    flight when the track ends and onTrackEnded starts the
 *    continuation; the listener arms the end-of-track timer
 *    during both fetches; the continuation lands first, takes
 *    its sleep branch, and the resolve lands after it and plays.
 * 2. onTrackEnded branch: the listener taps Next (or a queue
 *    item) while the ending track plays; the natural end fires
 *    before the resolve lands and takes the sleep branch; the
 *    resolve lands after it and plays.
 * 3. Repeat-transition branch: the same tap under repeat-one —
 *    the track loops before the resolve lands, the twin branch
 *    stops, and the resolve lands after it and plays.
 *
 * Fix at all three sites: the resolve job is cancelled inside
 * the sleep-stop branch, mirroring the timed expiry and the
 * transport stop. Each pin slices exactly its own branch and
 * asserts the cancel occurs exactly once in that slice — the
 * literal occurs at several other sites in the file (the timed
 * expiry, the transport stop, the resolve's own predecessor
 * cancel), so a whole-file assertion could be satisfied by a
 * sibling while its own branch stayed unprotected.
 *
 * QueueController is Media3-bound, so the shape is the pin
 * (house style; CI is the compile gate).
 */
class R17SweepContractTest {

    @Test
    fun continuationEndOfTrackSleepStopCancelsAnInFlightResolve() {
        assertTrue(
            "the continuation's end-of-track sleep branch must exist at all",
            controller().contains("if (fromTrackEnd &&"),
        )
        val branch = controller()
            .substringAfter("if (fromTrackEnd &&")
            .substringBefore("val timer = _sleepTimerState.value")
        assertEquals(
            "the continuation's end-of-track sleep stop must cancel " +
                "the resolve job exactly once inside its own branch — " +
                "a resolve captures no epoch, so round 16's bump " +
                "cannot stale it, and its landing would hand a track " +
                "to the player and play after this pause",
            1,
            occurrences(branch, "resolveJob?.cancel()"),
        )
        // Round 18 strengthening: the slice runs past the branch's
        // closing brace (it ends at the timed branch's timer read),
        // so presence alone would also pass with the cancel moved
        // to just AFTER the branch — dead code after its return, or
        // an unconditional cancel on every continuation landing.
        // Inside the branch the cancel sits between the epoch bump
        // and the branch's disarm; pin that placement (the same
        // gap round 17 closed for round 16's bump in this slice).
        assertTrue(
            "the cancel must sit inside the sleep branch, between " +
                "the epoch bump and the disarm — a cancel after the " +
                "branch's close is not this stop's cancel",
            branch.indexOf("queueEpoch += 1") <
                branch.indexOf("resolveJob?.cancel()") &&
                branch.indexOf("resolveJob?.cancel()") <
                branch.indexOf("cancelSleepTimer()"),
        )
    }

    @Test
    fun onTrackEndedSleepStopCancelsAnInFlightResolve() {
        assertTrue(
            "onTrackEnded must exist at all",
            controller().contains("private fun onTrackEnded()"),
        )
        val branch = controller()
            .substringAfter("private fun onTrackEnded()")
            .substringBefore("private fun continueWithAutoplay")
            .substringAfter("sleepTimerShouldStopAtTrackEnd")
            .substringBefore("return")
        assertEquals(
            "onTrackEnded's sleep stop must cancel the resolve job " +
                "exactly once inside its own branch — the function's " +
                "entry bump stales only the radio; a resolve started " +
                "by a Next tap moments before the natural end lands " +
                "after this pause and plays unless it is cancelled",
            1,
            occurrences(branch, "resolveJob?.cancel()"),
        )
        // Round 18 strengthening: this slice is already bounded
        // by the branch's return (a cancel below it falls outside
        // the slice), but presence alone would also pass with the
        // cancel moved below the orphan-clear helper — and the
        // fix's stated placement is that the cancel PRECEDES the
        // helper, so the helper sees the resolve inactive and
        // does not defer the flag to a dying resolve. Pin it.
        assertTrue(
            "the cancel must precede the orphan-clear helper — " +
                "below it, the helper would see a live resolve " +
                "and defer the flag to a fetch that is about to die",
            branch.indexOf("resolveJob?.cancel()") <
                branch.indexOf("clearResolvingIfOrphaned"),
        )
    }

    @Test
    fun repeatTransitionSleepStopCancelsAnInFlightResolve() {
        assertTrue(
            "the repeat-transition sleep stop must exist at all",
            controller().contains("MEDIA_ITEM_TRANSITION_REASON_REPEAT"),
        )
        val branch = controller()
            .substringAfter("MEDIA_ITEM_TRANSITION_REASON_REPEAT")
            .substringBefore("override fun onPlayerError")
        assertEquals(
            "the repeat-transition sleep stop must cancel the " +
                "resolve job exactly once inside its own branch — a " +
                "resolve started by a Next tap while the looping " +
                "track played captures no epoch, so round 16's bump " +
                "cannot stale it, and its landing would play after " +
                "this pause",
            1,
            occurrences(branch, "resolveJob?.cancel()"),
        )
        // Round 18 strengthening: the slice runs to the next
        // override, past the branch and the function's close, so
        // presence alone would also pass with the cancel moved
        // below the branch — where it would fire on EVERY media
        // item transition, not just the sleep stop. Inside the
        // branch the cancel sits between the continuation cancel
        // and the orphan-clear helper (whose deferral the fix's
        // placement claim also depends on); pin that placement.
        assertTrue(
            "the cancel must sit inside the sleep branch, between " +
                "the continuation cancel and the orphan-clear " +
                "helper — a cancel after the branch's close is " +
                "not this stop's cancel",
            branch.indexOf("autoplayJob?.cancel()") <
                branch.indexOf("resolveJob?.cancel()") &&
                branch.indexOf("resolveJob?.cancel()") <
                branch.indexOf("clearResolvingIfOrphaned"),
        )
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

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
