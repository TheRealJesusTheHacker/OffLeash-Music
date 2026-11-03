package dev.cyberdogs.offleash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 9 sweep contracts (confirmation round — breaking the
 * round-8 fixes), carried to the native flow (0.8.8):
 *
 * 1. GoogleSessionManager, re-read as ONE hand-merged file: the
 *    device flow's clearing terminal branches and non-clearing
 *    phase tails were round 7's check-then-apply shape —
 *    adjacent statements, not locked steps. A cancel + fresh
 *    start landing in a clearing branch's window let the stale
 *    tail wipe the NEWER grant and overwrite its phase. The
 *    native flow's heirs: the completion's three failure endings
 *    apply through ONE locked terminal step
 *    (`finishAttemptWithFailure`), the completion carries no
 *    direct failure publish at all, and startSignIn's awaiting
 *    publish is gated on the locked epoch-checked persist
 *    (`persistPendingAttemptIfCurrent`). The race windows have
 *    no runtime injection point, so the shape is the pin
 *    (house style).
 *
 * 2. AutoDownloadLikedWorker: the cookie road must be gated by
 *    the legacy DORMANCY rule at capture and at re-check (a
 *    Google session stored, or the sign-in seen marker set, makes
 *    the legacy session dormant — a merely-null bearer does not
 *    prove no Google session exists), and the session/generation
 *    capture must be the manager's consistent pair.
 */
class R9SweepContractTest {

    @Test
    fun completionFailureBranchesApplyThroughOneLockedStep() {
        // Native heir (0.8.8) of the poll loop's locked terminal
        // step: the completion's three failure endings (declined,
        // refused return, dead code) all end through
        // finishAttemptWithFailure — epoch check, state-guarded
        // clear, and phase publication as ONE locked step.
        val manager = manager()
        val helper = manager
            .substringAfter("private fun finishAttemptWithFailure(")
            .substringBefore("private fun persistPendingAttemptIfCurrent(")
        val block = synchronizedBlock(helper)
        assertTrue(
            "the terminal step's epoch check must live inside the lock",
            block.contains("if (epoch != flowEpoch) return"),
        )
        assertTrue(
            "the terminal step's clear must be guarded by the " +
                "attempt's state identity (the Granted discipline)",
            block.contains("pendingAttempt?.state == attempt.state") &&
                block.contains("clearPendingAttempt()"),
        )
        assertTrue(
            "the terminal step must publish its phase inside the lock",
            block.contains("_phase.value = GoogleSignInPhase.Failed(reason)"),
        )
        val completion = manager
            .substringAfter("fun completeSignIn(")
            .substringBefore("fun cancelSignIn()")
        assertTrue(
            "the Denied branch must end through the locked terminal step",
            completion.contains(
                "finishAttemptWithFailure(epoch, attempt, DENIED_REASON)",
            ),
        )
        assertTrue(
            "the exchange-failure branch must end through the locked " +
                "terminal step",
            completion.contains("EXCHANGE_FAILED_REASON"),
        )
        assertEquals(
            "all three failure endings (declined, refused return, " +
                "dead code) must end through the locked terminal step",
            3,
            Regex("finishAttemptWithFailure\\(")
                .findAll(completion).count(),
        )
        val denied = completion
            .substringAfter("RedirectResult.Denied")
            .substringBefore("is RedirectResult.Failed")
        assertTrue(
            "no bare clear may remain in the Denied branch itself",
            !denied.contains("clearPendingAttempt()"),
        )
        val refused = completion
            .substringAfter("is RedirectResult.Failed")
            .substringBefore("is RedirectResult.Code")
        assertTrue(
            "no bare clear may remain in the refused-return branch itself",
            !refused.contains("clearPendingAttempt()"),
        )
    }

    @Test
    fun completionFailureTailsNeverPublishDirectly() {
        // The device flow's non-clearing tails published through
        // publishPhaseIfCurrent; the native completion has no
        // non-clearing failure tails at all — every failure
        // ending clears through the one locked terminal step, so
        // no direct Failed publish may exist in the completion.
        val completion = manager()
            .substringAfter("fun completeSignIn(")
            .substringBefore("fun cancelSignIn()")
        assertTrue(
            "no direct Failed publish may remain in the completion — " +
                "every failure ending goes through the locked step",
            !completion.contains("_phase.value = GoogleSignInPhase.Failed"),
        )
        // startSignIn's two honest refusals (unconfigured build,
        // blank-after-trim client ID) publish Failed directly —
        // they are PRE-attempt (no attempt exists, no epoch can
        // race them), so both must sit before the persist step.
        val start = manager()
            .substringAfter("fun startSignIn()")
            .substringBefore("fun completeSignIn(")
        val persistAt = start.indexOf("persistPendingAttemptIfCurrent")
        assertTrue(
            "startSignIn's refusal publishes must precede the persist step",
            start.indexOf("_phase.value = GoogleSignInPhase.Failed") in
                0 until persistAt &&
                start.lastIndexOf(
                    "_phase.value = GoogleSignInPhase.Failed",
                ) < persistAt,
        )
    }

    @Test
    fun startSignInAwaitingPublishIsGatedByTheLockedPersistStep() {
        // The device flow published its awaiting state inside the
        // epoch-checked lock; the native start publishes only
        // after the locked epoch-checked persist ACCEPTS the
        // attempt — a raced cancel makes the persist refuse, and
        // no publish follows.
        val manager = manager()
        val start = manager
            .substringAfter("fun startSignIn()")
            .substringBefore("fun completeSignIn(")
        val gateAt = start.indexOf(
            "if (!persistPendingAttemptIfCurrent(epoch, attempt))",
        )
        assertTrue(
            "startSignIn must gate on the locked persist step",
            gateAt >= 0,
        )
        assertTrue(
            "the awaiting publish must come after the persist gate",
            start.indexOf("publishAwaitingReturn(attempt)") > gateAt,
        )
        assertTrue(
            "no awaiting publish of the fresh attempt may precede " +
                "the persist gate",
            !start.substring(0, gateAt).contains(
                "publishAwaitingReturn(attempt)",
            ),
        )
        val helper = manager
            .substringAfter("private fun persistPendingAttemptIfCurrent(")
            .substringBefore("    init {")
        val block = synchronizedBlock(helper)
        assertTrue(
            "the persist step's epoch check must live inside the lock",
            block.contains("if (epoch != flowEpoch) return false"),
        )
    }

    @Test
    fun autoDownloadCookieRoadIsDormancyGatedAndCapturesTheConsistentPair() {
        val body = doWorkBody()
        assertTrue(
            "the cookie road must be dormancy-gated at capture and at " +
                "re-check: a stored Google session makes the legacy " +
                "session dormant even when its bearer answers null",
            Regex("container\\.googleSessionManager\\.hasStoredSession\\(\\)")
                .findAll(body).count() >= 2,
        )
        assertTrue(
            "the seen marker must gate the cookie road at capture and " +
                "at re-check too: a Google sign-in that landed and was " +
                "wiped still retires the legacy fallback",
            Regex("container\\.googleSignInSeenMarker\\.seen\\(\\)")
                .findAll(body).count() >= 2,
        )
        assertTrue(
            "the capture must read the session and its generation as " +
                "the manager's one consistent pair, not two separate reads",
            body.contains("container.sessionManager.currentSessionWithGeneration()") &&
                body.contains("fetchSessionGeneration = if (session != null) generation else null"),
        )
    }

    private fun doWorkBody(): String {
        val worker = mainSource("java/dev/cyberdogs/offleash/account/AutoDownloadLikedWorker.kt")
        return worker
            .substringAfter("override suspend fun doWork()")
            .substringBefore("/** Enqueues the auto-download check")
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
