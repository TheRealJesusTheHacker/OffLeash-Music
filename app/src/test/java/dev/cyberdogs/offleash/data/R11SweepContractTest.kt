package dev.cyberdogs.offleash.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 11 sweep contracts (confirmation round — breaking the
 * round-10 Google locked applies):
 *
 * Round 10 guarded livePendingGrant's store-loaded candidate
 * adoption: the candidate is assigned only while `pendingGrant`
 * is still null, so an older candidate cannot overwrite a newer
 * in-memory grant. But the function's RETURN kept the pre-round-10
 * shape: it returned the candidate unconditionally. A fresh
 * persist landing between the store read and the adoption lock
 * (its grant is precisely the one the guard protects) leaves the
 * memory correct — and the caller (startSignIn / checkNow)
 * publishing and resuming the STALE candidate anyway: the screen
 * would show the superseded code while the poll loop runs the
 * newer grant, so an approval of the shown code is never
 * collected. The adoption step must hand back the standing
 * grant, not the candidate it carried in.
 *
 * The window has no runtime injection point (it needs a sealed
 * candidate standing while no grant is in memory, mid-issuance),
 * so the shape is the pin (house style, as with the round-10
 * F2 applies it completes).
 */
class R11SweepContractTest {

    @Test
    fun livePendingGrantAdoptionReturnsTheStandingGrantNotTheStaleCandidate() {
        // Round 12 presence guard (meta-audit, the r11a F2
        // pattern): the pin's literals are unique to this
        // function today (deletion-sensitive, verified by grep),
        // but the anchor itself was unasserted; the guard makes
        // that sensitivity explicit rather than accidental.
        assertTrue(
            "livePendingGrant must exist at all",
            manager().contains("private fun livePendingGrant()"),
        )
        val live = manager()
            .substringAfter("private fun livePendingGrant()")
            .substringBefore("private fun ensurePolling()")
        assertTrue(
            "the adoption must capture the standing grant inside its lock",
            live.contains("val standing = synchronized(lock)"),
        )
        val adoption = live.substringAfter("val standing = synchronized(lock)")
        assertTrue(
            "the adoption's in-lock step must keep round 10's " +
                "assign-only-while-null guard",
            adoption.contains("if (pendingGrant == null) pendingGrant = candidate"),
        )
        assertTrue(
            "livePendingGrant must return the standing grant its " +
                "adoption step found — returning the candidate " +
                "unconditionally republishes a superseded code while " +
                "the poll loop runs the newer grant",
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
