package dev.cyberdogs.offleash.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 10 sweep contracts (confirmation round — breaking the
 * round-9 fixes), carried to the native flow (0.8.8):
 *
 * The round-9 helpers locked the device flow's terminal applies,
 * but three guarded applies were still check-then-act in two
 * statements. Their native heirs carry the same disciplines:
 *
 * 1. startSignIn checked the epoch and THEN assigned + sealed
 *    the issued attempt in following statements — a cancel
 *    landing in the window stood the cancelled attempt back up
 *    in memory and in the sealed store, to be revived by the
 *    next startSignIn or by a fresh manager's restore
 *    (runtime-pinned in [R10GooglePersistRaceTest]). The persist
 *    is one locked epoch-checked step
 *    (`persistPendingAttemptIfCurrent`), and the publish is
 *    gated on its acceptance.
 * 2. The completion's Granted-branch clear and the terminal
 *    failure step (`finishAttemptWithFailure`) each guard on
 *    the standing attempt's state identity inside one
 *    `synchronized(lock)` step at their site. (The device flow's
 *    SlowDown write-back has no native heir — the native flow
 *    has no poll cadence to write back.)
 * 3. livePendingAttempt clears its expired candidate (and
 *    adopts a store-loaded candidate into memory) only under
 *    in-lock guards against the standing attempt still being
 *    that candidate.
 *
 * The windows in 2 and 3 have no runtime injection point, so the
 * shape is the pin (house style, as with r8b/r9 F4).
 */
class R10SweepContractTest {

    @Test
    fun startSignInPersistsTheAttemptInsideALockedEpochCheckedStep() {
        val manager = manager()
        val helper = manager
            .substringAfter("private fun persistPendingAttemptIfCurrent(")
            .substringBefore("    init {")
        val block = synchronizedBlock(helper)
        assertTrue(
            "the persist step's epoch check must live inside the lock",
            block.contains("if (epoch != flowEpoch) return false"),
        )
        assertTrue(
            "the in-memory assignment must live inside the locked step",
            block.contains("pendingAttempt = attempt"),
        )
        assertTrue(
            "the sealed save must live inside the locked step",
            block.contains("pendingAttemptStore?.save(attempt)"),
        )
        val start = manager
            .substringAfter("fun startSignIn()")
            .substringBefore("fun completeSignIn(")
        assertTrue(
            "startSignIn must persist through the locked step, " +
                "gated on its acceptance",
            start.contains(
                "if (!persistPendingAttemptIfCurrent(epoch, attempt))",
            ),
        )
        assertTrue(
            "no bare attempt assignment may remain in startSignIn — " +
                "the assignment outside the locked step is the " +
                "zombie window",
            !start.contains("pendingAttempt = attempt"),
        )
        assertTrue(
            "no bare attempt save may remain in startSignIn",
            !start.contains("pendingAttemptStore?.save(attempt)"),
        )
    }

    @Test
    fun completionAppliesAreLockedGuardedSteps() {
        val completion = manager()
            .substringAfter("fun completeSignIn(")
            .substringBefore("fun cancelSignIn()")
        val granted = completion
            .substringAfter("is CodeExchangeResult.Granted")
            .substringBefore("is CodeExchangeResult.Failed")
        val grantedBlock = synchronizedBlock(granted)
        assertTrue(
            "the Granted branch's state guard must live inside its lock",
            grantedBlock.contains("pendingAttempt?.state == attempt.state"),
        )
        assertTrue(
            "the Granted branch's clear must live inside its lock",
            grantedBlock.contains("clearPendingAttempt()"),
        )
        val terminal = manager()
            .substringAfter("private fun finishAttemptWithFailure(")
            .substringBefore("private fun persistPendingAttemptIfCurrent(")
        val terminalBlock = synchronizedBlock(terminal)
        assertTrue(
            "the terminal step's epoch check must live inside its lock",
            terminalBlock.contains("if (epoch != flowEpoch) return"),
        )
        assertTrue(
            "the terminal step's state guard must live inside its lock",
            terminalBlock.contains("pendingAttempt?.state == attempt.state"),
        )
        assertTrue(
            "the terminal step's clear must live inside its lock",
            terminalBlock.contains("clearPendingAttempt()"),
        )
        assertTrue(
            "the terminal step's failure publish must live inside its lock",
            terminalBlock.contains(
                "_phase.value = GoogleSignInPhase.Failed(reason)",
            ),
        )
    }

    @Test
    fun livePendingAttemptAppliesAreGuardedAgainstTheStandingAttempt() {
        val live = manager()
            .substringAfter("private fun livePendingAttempt()")
            .substringBefore("private fun publishAwaitingReturn")
        assertTrue(
            "the expired candidate's clear must re-check the " +
                "standing attempt's identity inside the lock " +
                "before clearing",
            live.contains("standing?.state == candidate.state"),
        )
        val clearBlock = synchronizedBlock(live)
        assertTrue(
            "the expired clear itself must live inside the lock",
            clearBlock.contains("clearPendingAttempt()"),
        )
        // Placement, not just presence (round 11 strengthening):
        // the guard asserted slice-wide above would stay green if
        // the standing-attempt read were hoisted OUT of the lock
        // (a stale read deciding an in-lock clear), and the
        // adoption asserted slice-wide below would stay green if
        // its null check and assignment were split outside the
        // lock (check-then-act). Both halves of the claim are
        // the placement, so both are asserted inside their
        // synchronized blocks.
        assertTrue(
            "the standing-attempt identity guard must live inside " +
                "the expired clear's lock, deciding the clear it " +
                "protects",
            clearBlock.contains("standing?.state == candidate.state"),
        )
        assertTrue(
            "the standing attempt must be RE-READ inside the lock " +
                "— a comparison in the block over a value read " +
                "beside it decides the clear on a stale snapshot",
            clearBlock.contains("val standing = pendingAttempt"),
        )
        assertTrue(
            "adopting a store-loaded candidate must not overwrite " +
                "a newer in-memory attempt assigned since the read",
            live.contains(
                "if (pendingAttempt == null) pendingAttempt = candidate",
            ),
        )
        val adoptionBlock = synchronizedBlock(
            live.substringAfter("if (inMemory == null)"),
        )
        assertTrue(
            "the candidate adoption's null check and assignment " +
                "must be one locked step, not a check beside the lock",
            adoptionBlock.contains(
                "if (pendingAttempt == null) pendingAttempt = candidate",
            ),
        )
    }

    /** The brace-matched body of the first `synchronized(lock) { ... }` in [text]. */
    private fun synchronizedBlock(text: String): String {
        val start = text.indexOf("synchronized(lock)")
        assertTrue("a synchronized(lock) step must be present", start >= 0)
        val open = text.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced synchronized block")
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
