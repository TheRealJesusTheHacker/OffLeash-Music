package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 15 sweep contracts (confirmation round — breaking round
 * 14's radio fix):
 *
 * Round 14 recorded the radio fetch in `radioJob` so the
 * orphan-clear helper could see its ownership of the resolving
 * flag. Two of the flag's OTHER deferral branches were left
 * consulting only the resolve job, and startRadio itself never
 * cancelled a superseded predecessor:
 *
 * 1. startRadio re-entry overwrote `radioJob` without cancelling
 *    the old fetch. The old fetch — epoch-stale but alive — still
 *    landed in its epoch branch, whose clear consulted only
 *    `resolveJob`, and cleared the flag while the NEW radio fetch
 *    owned it. The same branch cleared the flag under a live
 *    CONTINUATION when the fetch had been staled by next()'s
 *    epoch bump (next() starts the continuation it supersedes
 *    the radio for). Fix: startRadio cancels its predecessor
 *    before recording the new fetch (resolveAndPlay's own
 *    discipline), and the epoch branch's clear defers to every
 *    other live owner — resolve, continuation, or a newer radio
 *    fetch (this fetch's own ownership stated by identity).
 *
 * 2. The continuation's abandon branch ("their own resolve owns
 *    the flag now") likewise consulted only `resolveJob`. A queue
 *    mutation that starts no resolve — addToQueue/playNext
 *    replace the track list object — trips that branch while a
 *    radio fetch started mid-continuation is still in flight,
 *    and the clear killed the radio fetch's loading state. Fix:
 *    the branch defers to a live radio fetch too.
 *
 * QueueController is Media3-bound, so the shape is the pin
 * (house style; CI is the compile gate).
 */
class R15SweepContractTest {

    @Test
    fun startRadioCancelsASupersededRadioFetchBeforeRecordingTheNewOne() {
        assertTrue(
            "startRadio must exist at all",
            controller().contains("fun startRadio(track: Track)"),
        )
        val body = controller()
            .substringAfter("fun startRadio(track: Track)")
            .substringBefore("private fun onTrackEnded()")
        assertTrue(
            "startRadio must cancel the previous radio fetch " +
                "before recording the new one — an uncancelled " +
                "predecessor still lands in its epoch branch and " +
                "clears the flag its successor owns",
            body.contains("radioJob?.cancel()") &&
                body.indexOf("radioJob?.cancel()") <
                body.indexOf("radioJob = scope.launch"),
        )
    }

    @Test
    fun staleRadioLandingClearsTheFlagOnlyWhenNoOtherFetchOwnsIt() {
        assertTrue(
            "the radio epoch branch must exist at all",
            controller().contains("if (epochAtStart != queueEpoch)"),
        )
        val branch = controller()
            .substringAfter("if (epochAtStart != queueEpoch)")
            .substringBefore("return@launch")
        assertTrue(
            "a superseded radio landing's flag clear must defer " +
                "to a live resolve, a live continuation, and a " +
                "newer radio fetch (its own ownership stated by " +
                "identity, or the recorded radio job no longer " +
                "active) — the resolve-only guard cleared the " +
                "flag under whichever fetch the takeover started",
            branch.contains("resolveJob?.isActive != true") &&
                branch.contains("autoplayJob?.isActive != true") &&
                branch.contains("radioJob === currentCoroutineContext()[Job]") &&
                branch.contains("radioJob?.isActive != true") &&
                branch.contains("setResolving(false)"),
        )
        // Round 17 strengthening (the residual round 16's audit
        // handed over): the legs' presence does not pin the
        // OPERATOR between the identity leg and the radio-inactive
        // leg — an || narrowed to && would pass every assertion
        // above while making the clear almost unreachable (a fetch
        // that IS the recorded job is normally still active at its
        // own landing). The disjunction itself is part of the claim.
        assertTrue(
            "the identity leg and the radio-inactive leg must be " +
                "DISJUNCTIVE (||), not conjunctive — with && the " +
                "clear would require this fetch to be the recorded " +
                "job and that job inactive at the same time",
            branch.contains("currentCoroutineContext()[Job] ||"),
        )
    }

    @Test
    fun continuationAbandonClearRespectsALiveRadioFetch() {
        assertTrue(
            "the continuation's queue-takeover guard must exist at all",
            controller().contains("queue.tracks !== queueAtRequest"),
        )
        val branch = controller()
            .substringAfter("queue.tracks !== queueAtRequest")
            .substringBefore("return@launch")
        assertTrue(
            "an abandoned continuation's flag clear must defer " +
                "to a live radio fetch as well as a live resolve — " +
                "a no-resolve queue mutation (addToQueue/playNext) " +
                "trips this branch while a radio fetch started " +
                "mid-continuation is still in flight",
            branch.contains("resolveJob?.isActive != true") &&
                branch.contains("radioJob?.isActive != true") &&
                branch.contains("setResolving(false)"),
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
