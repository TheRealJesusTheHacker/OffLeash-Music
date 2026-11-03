package dev.cyberdogs.offleash.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 11 sweep contracts (confirmation round — breaking the
 * round-10 Google locked applies), carried to the native flow
 * (0.8.8):
 *
 * Round 10 guarded livePendingAttempt's store-loaded candidate
 * adoption: the candidate is assigned only while `pendingAttempt`
 * is still null, so an older candidate cannot overwrite a newer
 * in-memory attempt. But the function's RETURN kept the
 * pre-round-10 shape: it returned the candidate unconditionally.
 * A fresh persist landing between the store read and the
 * adoption lock (its attempt is precisely the one the guard
 * protects) leaves the memory correct — and the caller
 * (startSignIn) publishing and resuming the STALE candidate
 * anyway: the screen would hand the browser a superseded
 * authorization while the manager tracks the newer attempt, so
 * the return's state could never match. The adoption step must
 * hand back the standing attempt, not the candidate it carried
 * in.
 *
 * The window has no runtime injection point (it needs a sealed
 * candidate standing while no attempt is in memory,
 * mid-issuance), so the shape is the pin (house style, as with
 * the round-10 F2 applies it completes).
 */
class R11SweepContractTest {

    @Test
    fun livePendingAttemptAdoptionReturnsTheStandingAttemptNotTheStaleCandidate() {
        // Round 12 presence guard (meta-audit, the r11a F2
        // pattern): the pin's literals are unique to this
        // function today (deletion-sensitive, verified by grep),
        // but the anchor itself was unasserted; the guard makes
        // that sensitivity explicit rather than accidental.
        assertTrue(
            "livePendingAttempt must exist at all",
            manager().contains("private fun livePendingAttempt()"),
        )
        val live = manager()
            .substringAfter("private fun livePendingAttempt()")
            .substringBefore("private fun publishAwaitingReturn")
        assertTrue(
            "the adoption must capture the standing attempt inside its lock",
            live.contains("val standing = synchronized(lock)"),
        )
        val adoption = live.substringAfter("val standing = synchronized(lock)")
        assertTrue(
            "the adoption's in-lock step must keep round 10's " +
                "assign-only-while-null guard",
            adoption.contains(
                "if (pendingAttempt == null) pendingAttempt = candidate",
            ),
        )
        assertTrue(
            "livePendingAttempt must return the standing attempt " +
                "its adoption step found — returning the candidate " +
                "unconditionally republishes a superseded attempt " +
                "whose state the return could never match",
            adoption.contains("return standing ?: candidate"),
        )
    }

    private fun manager(): String =
        mainSource("java/dev/cyberdogs/offleash/data/GoogleSessionManager.kt")

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
