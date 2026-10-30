package dev.cyberdogs.offleash.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 8 sweep contracts (merged at integration — both round-8
 * lanes created this class independently; the finisher combined
 * them, tests unchanged except as noted):
 *
 * From lane r8b (propagation of the round-7 pattern classes):
 * [GoogleSessionManager.adopt]'s two phase publications were
 * check-then-publish OUTSIDE the manager lock — the identity-
 * Rejected branch published Failed(IDENTITY_REJECTED_REASON)
 * unconditionally after one epoch check, and the save-failure
 * tail (if (!saved)) published Failed(STORE_REASON) in the same
 * unlocked shape. Both are now one synchronized(lock) step each,
 * gated on the epoch. The pins brace-match the locked blocks AND
 * assert the epoch check itself lives inside the block (round
 * 7's refreshIdentity pins asserted only the publish's placement
 * — that half was added to those pins in round 8 as well).
 *
 * From lane r8a (breaking the round-7 fixes):
 * 1. AutoDownloadLikedWorker's cookie road must re-check the
 *    session GENERATION, never the AuthSession by reference:
 *    rotation replaces the instance for the same account (pinned
 *    at runtime by R8SessionGenerationTest), so the round-7
 *    reference comparison read a healthy session as stale on the
 *    very response the fetch itself carried.
 * 2. The poll Granted branch must clear the pending grant only
 *    while it is still THAT grant (the SlowDown branch's
 *    deviceCode discipline): it was the one terminal branch that
 *    cleared unconditionally, ahead of adopt()'s epoch check.
 * 3. signOut must bump the flow epoch again at its locked wipe,
 *    and refreshIdentity's Rejected outcome must refuse when no
 *    session stands (the runtime half is
 *    R8GoogleSignOutWindowTest).
 * 4. Playlist row actions must pass the row's full Track to the
 *    guarded mutations: a videoId cannot tell duplicate copies
 *    of one track apart.
 *
 * Merge note: both lanes pinned adopt()'s Rejected publish
 * independently under the same test name; the r8b brace-matched
 * version is kept (it also asserts no phase publication remains
 * outside the locked block in the branch, which subsumes the
 * r8a slice assertions for the same branch).
 */
class R8SweepContractTest {

    @Test
    fun adoptRejectedPublishesInsideTheEpochCheckedLock() {
        val rejected = adopt()
            .substringAfter("if (lookup is IdentityLookup.Rejected)")
            .substringBefore("val selected")
        val block = synchronizedBlock(rejected)
        assertTrue(
            "the Rejected branch's epoch check must live inside the " +
                "locked step, not before it",
            block.contains("if (epoch == flowEpoch)"),
        )
        assertTrue(
            "adopt's Rejected outcome must publish its phase inside " +
                "the epoch-checked synchronized block, not after it",
            block.contains(
                "_phase.value = GoogleSignInPhase.Failed(IDENTITY_REJECTED_REASON)",
            ),
        )
        assertTrue(
            "no phase publication may remain outside the locked block " +
                "in adopt's Rejected branch",
            !rejected.substringAfter(block).contains("_phase.value"),
        )
    }


    @Test
    fun adoptStoreFailurePublishesInsideTheEpochCheckedLock() {
        val tail = adopt()
            .substringAfter("if (!saved) {")
            .substringBefore("sign-in complete")
        val block = synchronizedBlock(tail)
        assertTrue(
            "the save-failure tail's epoch check must live inside the " +
                "locked step, not before it",
            block.contains("if (epoch == flowEpoch)"),
        )
        assertTrue(
            "adopt's store-failure outcome must publish its phase inside " +
                "the epoch-checked synchronized block, not after it",
            block.contains(
                "_phase.value = GoogleSignInPhase.Failed(STORE_REASON)",
            ),
        )
        assertTrue(
            "no phase publication may remain outside the locked block " +
                "in adopt's save-failure tail",
            !tail.substringAfter(block).contains("_phase.value"),
        )
    }


    @Test
    fun autoDownloadCookieRoadRechecksTheSessionGenerationNotTheInstance() {
        val body = doWorkBody()
        assertTrue(
            "the cookie road must capture the session generation before the fetch",
            body.contains("fetchSessionGeneration"),
        )
        assertTrue(
            "the cookie road must re-check the generation, not the instance",
            body.contains("sessionGeneration() == fetchSessionGeneration"),
        )
        assertTrue(
            "the round-7 reference comparison must be gone — rotation " +
                "replaces the instance for the same account",
            !body.contains("=== fetchSession"),
        )
        assertTrue(
            "the bearer road's token comparison stands (its refresh cost " +
                "is documented and its re-trigger is app start / Library)",
            body.contains("container.currentBearerToken() == fetchBearer"),
        )
    }


    @Test
    fun pollGrantedBranchClearsOnlyItsOwnGrant() {
        val poll = manager()
            .substringAfter("private suspend fun pollPendingGrant()")
            .substringBefore("private suspend fun adopt(")
        val granted = poll
            .substringAfter("is TokenPollResult.Granted")
            .substringBefore("TokenPollResult.Pending")
        assertTrue(
            "the Granted branch must guard its clear with the deviceCode " +
                "identity of the grant it polled (the SlowDown discipline)",
            granted.contains("pendingGrant?.deviceCode == grant.deviceCode"),
        )
        assertTrue(
            "the clear must sit behind the deviceCode guard, never ahead of it",
            granted.indexOf("clearPendingGrant()") >
                granted.indexOf("pendingGrant?.deviceCode"),
        )
    }


    @Test
    fun signOutBumpsTheEpochAgainAtTheWipeAndRejectedRefreshRequiresASession() {
        val signOut = manager()
            .substringAfter("suspend fun signOut()")
            .substringBefore("private companion object")
        val grab = signOut
            .substringAfter("val session = synchronized(lock)")
            .substringBefore("if (session != null)")
        assertTrue(
            "signOut's locked wipe must bump the epoch again, so a refresh " +
                "that started after the first bump (while the grant wipe " +
                "was in flight) is stale by the time it applies",
            grab.contains("flowEpoch++"),
        )
        val refresh = manager()
            .substringAfter("suspend fun refreshIdentity()")
            .substringBefore("suspend fun signOut()")
        val rejected = refresh
            .substringAfter("is IdentityLookup.Rejected")
            .substringBefore("is IdentityLookup.Failed")
        assertTrue(
            "the Rejected outcome must refuse when the session it was " +
                "fetched for no longer stands",
            rejected.contains("stored ?: return") || rejected.contains("if (stored == null) return"),
        )
    }


    @Test
    fun playlistRowActionsPassTheFullTrackToTheGuardedMutations() {
        val library = mainSource("java/dev/cyberdogs/offleash/ui/library/LibraryScreen.kt")
        val guardedMoves = Regex(
            "moveTrack\\(\\s*playlist\\.id,\\s*index,\\s*index [+-] 1,\\s*track,",
        ).findAll(library).count()
        assertTrue(
            "both move buttons must pass the row's full Track (round 8: " +
                "videoId cannot tell duplicate copies apart)",
            guardedMoves == 2,
        )
        assertTrue(
            "the row delete must pass the row's full Track too",
            Regex("removeTrackAt\\(\\s*playlist\\.id,\\s*index,\\s*track,")
                .containsMatchIn(library),
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


    private fun adopt(): String =
        mainSource("java/dev/cyberdogs/offleash/data/GoogleSessionManager.kt")
            .substringAfter("private suspend fun adopt(")


    private fun manager(): String =
        mainSource("java/dev/cyberdogs/offleash/data/GoogleSessionManager.kt")


    private fun doWorkBody(): String {
        val worker = mainSource("java/dev/cyberdogs/offleash/account/AutoDownloadLikedWorker.kt")
        return worker
            .substringAfter("override suspend fun doWork()")
            .substringBefore("/** Enqueues the auto-download check")
    }


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
