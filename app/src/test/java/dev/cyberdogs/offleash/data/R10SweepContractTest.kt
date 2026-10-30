package dev.cyberdogs.offleash.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 10 sweep contracts (confirmation round — breaking the
 * round-9 fixes):
 *
 * GoogleSessionManager's census, re-run after round 9's helpers:
 * round 9 locked the poll loop's TERMINAL applies
 * (`finishPollWithFailure` / `publishPhaseIfCurrent`), but three
 * guarded applies were still check-then-act in two statements,
 * and runSignIn's grant persist was the largest of them:
 *
 * 1. runSignIn checked the epoch and THEN assigned + sealed the
 *    issued grant in following statements — a cancel landing in
 *    the window stood the cancelled code back up in memory and
 *    in the sealed store, to be revived by the next startSignIn
 *    or by a fresh manager's restore (runtime-pinned in
 *    [R10GooglePersistRaceTest]). The persist is now one locked
 *    epoch-checked step (`persistPendingGrantIfCurrent`).
 * 2. The poll Granted branch's deviceCode-guarded clear and the
 *    SlowDown branch's deviceCode-guarded write-back read the
 *    standing grant and then applied unconditionally — each is
 *    now one `synchronized(lock)` step at its site.
 * 3. livePendingGrant cleared its expired candidate (and adopted
 *    a store-loaded candidate into memory) unconditionally —
 *    both applies are now guarded inside the lock against the
 *    standing grant still being that candidate.
 *
 * The windows in 2 and 3 have no runtime injection point, so the
 * shape is the pin (house style, as with r8b/r9 F4).
 */
class R10SweepContractTest {

    @Test
    fun runSignInPersistsTheGrantInsideALockedEpochCheckedStep() {
        val manager = manager()
        val helper = manager
            .substringAfter("private fun persistPendingGrantIfCurrent(")
            .substringBefore("    init {")
        val block = synchronizedBlock(helper)
        assertTrue(
            "the persist step's epoch check must live inside the lock",
            block.contains("if (epoch != flowEpoch) return false"),
        )
        assertTrue(
            "the in-memory assignment must live inside the locked step",
            block.contains("pendingGrant = grant"),
        )
        assertTrue(
            "the sealed save must live inside the locked step",
            block.contains("pendingGrantStore?.save(grant)"),
        )
        val runSignIn = manager
            .substringAfter("private suspend fun runSignIn()")
            .substringBefore("private suspend fun pollPendingGrant()")
        assertTrue(
            "runSignIn must persist through the locked step",
            runSignIn.contains("persistPendingGrantIfCurrent(epoch, grant)"),
        )
        assertTrue(
            "no bare grant assignment may remain in runSignIn — the " +
                "assignment outside the locked step is the zombie window",
            !runSignIn.contains("pendingGrant = grant"),
        )
        assertTrue(
            "no bare grant save may remain in runSignIn",
            !runSignIn.contains("pendingGrantStore?.save(grant)"),
        )
    }

    @Test
    fun pollGrantedAndSlowDownAppliesAreLockedGuardedSteps() {
        val poll = manager()
            .substringAfter("private suspend fun pollPendingGrant()")
            .substringBefore("private suspend fun adopt(")
        val granted = poll
            .substringAfter("is TokenPollResult.Granted")
            .substringBefore("TokenPollResult.Pending")
        val grantedBlock = synchronizedBlock(granted)
        assertTrue(
            "the Granted branch's deviceCode guard must live inside its lock",
            grantedBlock.contains("pendingGrant?.deviceCode == grant.deviceCode"),
        )
        assertTrue(
            "the Granted branch's clear must live inside its lock",
            grantedBlock.contains("clearPendingGrant()"),
        )
        val slowDown = poll
            .substringAfter("TokenPollResult.SlowDown")
            .substringBefore("TokenPollResult.Denied")
        val slowBlock = synchronizedBlock(slowDown)
        assertTrue(
            "the SlowDown write-back's deviceCode guard must live inside its lock",
            slowBlock.contains("pendingGrant?.deviceCode == grant.deviceCode"),
        )
        assertTrue(
            "the SlowDown write-back's assignment must live inside its lock",
            slowBlock.contains("pendingGrant = slowed"),
        )
        assertTrue(
            "the SlowDown write-back's save must live inside its lock",
            slowBlock.contains("pendingGrantStore?.save(slowed)"),
        )
    }

    @Test
    fun livePendingGrantAppliesAreGuardedAgainstTheStandingGrant() {
        val live = manager()
            .substringAfter("private fun livePendingGrant()")
            .substringBefore("private fun ensurePolling()")
        assertTrue(
            "the expired candidate's clear must re-check the standing " +
                "grant's identity inside the lock before clearing",
            live.contains("standing?.deviceCode == candidate.deviceCode"),
        )
        val clearBlock = synchronizedBlock(live)
        assertTrue(
            "the expired clear itself must live inside the lock",
            clearBlock.contains("clearPendingGrant()"),
        )
        // Placement, not just presence (round 11 strengthening —
        // the round-8/10 precedent this file's Granted/SlowDown
        // pin already follows): the guard asserted slice-wide
        // above would stay green if the standing-grant read were
        // hoisted OUT of the lock (a stale read deciding an
        // in-lock clear), and the adoption asserted slice-wide
        // below would stay green if its null check and assignment
        // were split outside the lock (check-then-act). Both
        // halves of the claim are the placement, so both are now
        // asserted inside their synchronized blocks.
        assertTrue(
            "the standing-grant identity guard must live inside the " +
                "expired clear's lock, deciding the clear it protects",
            clearBlock.contains("standing?.deviceCode == candidate.deviceCode"),
        )
        assertTrue(
            "the standing grant must be RE-READ inside the lock — a " +
                "comparison in the block over a value read beside it " +
                "decides the clear on a stale snapshot",
            clearBlock.contains("val standing = pendingGrant"),
        )
        assertTrue(
            "adopting a store-loaded candidate must not overwrite a " +
                "newer in-memory grant assigned since the read",
            live.contains("if (pendingGrant == null) pendingGrant = candidate"),
        )
        val adoptionBlock = synchronizedBlock(
            live.substringAfter("if (inMemory == null)"),
        )
        assertTrue(
            "the candidate adoption's null check and assignment must " +
                "be one locked step, not a check beside the lock",
            adoptionBlock.contains(
                "if (pendingGrant == null) pendingGrant = candidate",
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
