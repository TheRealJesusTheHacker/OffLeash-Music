package dev.cyberdogs.offleash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 9 sweep contracts (confirmation round — breaking the
 * round-8 fixes):
 *
 * 1. GoogleSessionManager, re-read as ONE hand-merged file: the
 *    poll loop's clearing terminal branches (expiry-at-top,
 *    Denied, Expired) and the non-clearing phase tails (poll
 *    transient Failed, poll/runSignIn unexpected catches,
 *    runSignIn's issuance refusal and its post-persist awaiting
 *    publish) were still round 7's check-then-apply shape —
 *    adjacent statements, not locked steps — while their siblings
 *    (Granted's deviceCode-guarded clear, adopt's and
 *    refreshIdentity's locked publishes) had moved on. A cancel +
 *    fresh start landing in a clearing branch's window let the
 *    stale tail wipe the NEWER grant and overwrite its phase.
 *    All of them now apply through one locked step each
 *    (`finishPollWithFailure` / `publishPhaseIfCurrent` / the
 *    locked publishAwaiting). The race windows have no runtime
 *    injection point, so the shape is the pin (house style).
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
    fun pollClearingTerminalBranchesApplyThroughOneLockedStep() {
        val manager = manager()
        val helper = manager
            .substringAfter("private fun finishPollWithFailure(")
            .substringBefore("    init {")
        val block = synchronizedBlock(helper)
        assertTrue(
            "the terminal step's epoch check must live inside the lock",
            block.contains("if (epoch != flowEpoch) return"),
        )
        assertTrue(
            "the terminal step's clear must be guarded by the polled " +
                "grant's deviceCode (the Granted/SlowDown discipline)",
            block.contains("pendingGrant?.deviceCode == grant.deviceCode") &&
                block.contains("clearPendingGrant()"),
        )
        assertTrue(
            "the terminal step must publish its phase inside the lock",
            block.contains("_phase.value = GoogleSignInPhase.Failed(reason)"),
        )
        val poll = pollBody()
        assertTrue(
            "the Denied branch must end through the locked terminal step",
            poll.contains("finishPollWithFailure(epoch, grant, DENIED_REASON)"),
        )
        assertEquals(
            "expiry-at-top and the Expired branch must both end through " +
                "the locked terminal step",
            2,
            Regex("finishPollWithFailure\\(epoch, grant, EXPIRED_REASON\\)")
                .findAll(poll).count(),
        )
        val denied = poll
            .substringAfter("TokenPollResult.Denied")
            .substringBefore("TokenPollResult.Expired")
        assertTrue(
            "no bare clear may remain in the Denied branch itself",
            !denied.contains("clearPendingGrant()"),
        )
        val expiryTop = poll.substringBefore("val poll =")
        assertTrue(
            "no bare clear may remain in the expiry-at-top branch",
            !expiryTop.contains("clearPendingGrant()"),
        )
        // Round 10 strengthening: the Expired branch was the one
        // clearing branch whose no-bare-clear half went unasserted
        // (its routing was counted above, but an ADDITIONAL bare
        // clear beside the helper call would have passed). The
        // claim covers all three clearing branches, so all three
        // slices are asserted.
        val expired = poll
            .substringAfter("TokenPollResult.Expired")
            .substringBefore("is TokenPollResult.Failed")
        assertTrue(
            "no bare clear may remain in the Expired branch itself",
            !expired.contains("clearPendingGrant()"),
        )
    }

    @Test
    fun nonClearingPhaseTailsPublishThroughTheLockedHelper() {
        val manager = manager()
        val helper = manager
            .substringAfter("private fun publishPhaseIfCurrent(")
            .substringBefore("private fun finishPollWithFailure(")
        val block = synchronizedBlock(helper)
        assertTrue(
            "the helper's epoch check must live inside its lock",
            block.contains("if (epoch == flowEpoch)"),
        )
        assertTrue(
            "the helper must publish inside its lock",
            block.contains("_phase.value = phase"),
        )
        val runSignIn = manager
            .substringAfter("private suspend fun runSignIn()")
            .substringBefore("private suspend fun pollPendingGrant()")
        assertTrue(
            "runSignIn's issuance refusal and its unexpected-failure " +
                "tail must both publish through the locked helper",
            Regex("publishPhaseIfCurrent\\(").findAll(runSignIn).count() >= 2,
        )
        val poll = pollBody()
        assertTrue(
            "the poll loop's transient-Failed branch and its " +
                "unexpected-failure tail must both publish through " +
                "the locked helper",
            Regex("publishPhaseIfCurrent\\(").findAll(poll).count() >= 2,
        )
    }

    @Test
    fun runSignInAwaitingPublishIsInsideTheEpochCheckedLock() {
        val runSignIn = manager()
            .substringAfter("private suspend fun runSignIn()")
            .substringBefore("private suspend fun pollPendingGrant()")
        val block = synchronizedBlock(runSignIn)
        assertTrue(
            "the awaiting publish's epoch check must live inside the lock",
            block.contains("if (epoch != flowEpoch) return"),
        )
        assertTrue(
            "the awaiting publish must live inside the locked step",
            block.contains("publishAwaiting(grant)"),
        )
        assertTrue(
            "no awaiting publish may remain outside the locked step " +
                "in runSignIn",
            !runSignIn.substringAfter(block).contains("publishAwaiting(grant)"),
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

    private fun pollBody(): String = manager()
        .substringAfter("private suspend fun pollPendingGrant()")
        .substringBefore("private suspend fun adopt(")

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
