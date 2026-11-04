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
  *
 * 0.9.0 (owner order, 2026-11-04): this round's adopt/sign-out/
 * auto-download pins belonged to GoogleSessionManager and the
 * account worker — both deleted with the sign-in product. Those
 * tests are retired with their subjects; the playlist
 * guarded-mutation pin below is the round's surviving claim and
 * stands unchanged.
*/
class R8SweepContractTest {

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
