package dev.cyberdogs.offleash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 7 sweep contracts (merged at integration — both round-7
 * lanes created this class independently; the finisher combined
 * them into one file, tests unchanged):
 *
 * From lane r7a:
 *
 * 1. [GoogleSessionManager.refreshIdentity]'s Rejected and Failed
 *    outcomes must publish their uiState INSIDE the same
 *    synchronized(lock) step that checks the flow epoch (round 7).
 *    Round 6 checked the epoch (thrice, for Rejected) and then
 *    published OUTSIDE the lock: a sign-out landing between the
 *    last check and the assignment — its epoch bump is a locked
 *    step, but its own publication follows a network revocation —
 *    could still be overwritten by the stale outcome (a
 *    NEEDS_SIGN_IN "revoked" ending, or a signed-in copy with
 *    health UNKNOWN read from the pre-sign-out state). These pins
 *    brace-match the locked blocks, so a publish moved back
 *    outside fails them. (The race window has no injection point,
 *    so no runtime test can force it deterministically; the shape
 *    is the pin, in the house contract style.)
 *
 * 2. The playlist screen's move buttons must pass the row's
 *    videoId to the identity-guarded moveTrack (round 7), exactly
 *    as its delete button passes it to removeTrackAt (round 6).
 *
 * From lane r7b:
 *
 * 3. Auto-download of Liked fetched the signed-in liked list
 *    through a blocking client call and handed it to
 *    [DownloadManager.enqueueMissing] unconditionally. The
 *    worker's decided sign-out behaviour ("does nothing while
 *    signed out") covered a run that STARTED signed out, but a
 *    sign-out — or a sign-out + sign-in as a different account —
 *    landing while the fetch was out left the run holding the
 *    PREVIOUS account's list, and it enqueued those tracks for
 *    download anyway: a stale async outcome applying after the
 *    session it was fetched under ended (the guarded-mutation
 *    census, round 7). The worker now captures the exact
 *    credential the fetch rides on BEFORE the fetch and re-checks
 *    it is still current BEFORE the enqueue; the runtime half of
 *    the census (DownloadManager's own stale applies) is pinned
 *    by DownloadManagerTest's round-7 tests.
  *
 * 0.9.0 (owner order, 2026-11-04): sections 1 and 3 of this round
 * pinned GoogleSessionManager's refresh publishes and the
 * auto-download worker's credential capture — both deleted with
 * the sign-in product. Those tests are retired with their
 * subjects; the playlist guarded-move pin below is the round's
 * surviving claim and stands unchanged.
*/
class R7SweepContractTest {

    @Test
    fun playlistMoveButtonsPassTheRowsIdentityToTheGuardedMove() {
        // Literal updated in round 8 (purpose unchanged): the
        // buttons now pass the row's full Track value instead of
        // its videoId — a videoId cannot tell duplicate copies of
        // one track apart (see R8PlaylistGuardTest). The pin's
        // claim stands: both move buttons pass the tapped row's
        // identity to the identity-guarded moveTrack.
        val library = mainSource("java/dev/cyberdogs/offleash/ui/library/LibraryScreen.kt")
        val guardedMoves = Regex(
            "moveTrack\\(\\s*playlist\\.id,\\s*index,\\s*index [+-] 1,\\s*track,",
        ).findAll(library).count()
        assertEquals(
            "both move buttons (up and down) must pass the tapped row's " +
                "identity to the identity-guarded moveTrack",
            2,
            guardedMoves,
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
