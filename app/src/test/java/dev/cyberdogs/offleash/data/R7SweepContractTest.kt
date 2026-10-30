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
 */
class R7SweepContractTest {

    @Test
    fun rejectedRefreshPublishesInsideTheEpochCheckedLock() {
        val refresh = manager()
            .substringAfter("suspend fun refreshIdentity()")
            .substringBefore("suspend fun signOut()")
        val rejected = refresh
            .substringAfter("is IdentityLookup.Rejected")
            .substringBefore("is IdentityLookup.Failed")
        val block = synchronizedBlock(rejected)
        assertTrue(
            "the Rejected branch's epoch check must live inside the " +
                "locked step — a guard deleted from inside an otherwise " +
                "well-shaped block must fail this pin (round 8)",
            block.contains("if (epochAtStart != flowEpoch) return"),
        )
        assertTrue(
            "the Rejected outcome must publish its signed-out state inside " +
                "the epoch-checked synchronized block, not after it",
            block.contains("_uiState.value = SessionUiState("),
        )
        assertTrue(
            "no uiState publication may remain outside the locked block " +
                "in the Rejected branch",
            !rejected.substringAfter(block).contains("_uiState.value"),
        )
    }

    @Test
    fun failedRefreshPublishesInsideTheEpochCheckedLock() {
        val refresh = manager()
            .substringAfter("suspend fun refreshIdentity()")
            .substringBefore("suspend fun signOut()")
        val failed = refresh.substringAfter("is IdentityLookup.Failed")
        val block = synchronizedBlock(failed)
        assertTrue(
            "the Failed branch's epoch check must live inside the " +
                "locked step — a guard deleted from inside an otherwise " +
                "well-shaped block must fail this pin (round 8)",
            block.contains("if (epochAtStart != flowEpoch) return"),
        )
        assertTrue(
            "the Failed outcome must read-and-republish uiState inside the " +
                "epoch-checked synchronized block, not outside it",
            block.contains("_uiState.value = state.copy("),
        )
        assertTrue(
            "no uiState publication may remain outside the locked block " +
                "in the Failed branch",
            !failed.substringAfter(block).contains("_uiState.value ="),
        )
    }

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

    @Test
    fun autoDownloadCapturesTheFetchCredentialBeforeFetching() {
        val body = doWorkBody()
        val captureAt = body.indexOf("fetchBearer" + " = " + "bearer")
        val fetchAt = body.indexOf("likedSongsOAuth(bearer)")
        assertTrue("the fetch credential must be captured", captureAt >= 0)
        assertTrue("the liked fetch must exist", fetchAt >= 0)
        assertTrue(
            "the credential must be captured BEFORE the fetch it rides on",
            captureAt < fetchAt,
        )
        assertTrue(
            "the cookie road's session must be captured too (round 8: " +
                "as its generation — the instance is replaced by " +
                "rotation for the same account; see R8SessionGenerationTest)",
            body.contains("fetchSessionGeneration = if (session != null)"),
        )
    }

    @Test
    fun autoDownloadRechecksTheCredentialBeforeEnqueueing() {
        val body = doWorkBody()
        val guardAt = body.indexOf("if (!stillCurrent) return Result.success()")
        val enqueueAt = body.indexOf("enqueueMissing(liked.tracks)")
        assertTrue("the stale-apply guard must exist", guardAt >= 0)
        assertTrue("the enqueue must exist", enqueueAt >= 0)
        assertTrue(
            "a run whose fetching credential is no longer current must " +
                "not enqueue the previous account's liked tracks",
            guardAt < enqueueAt,
        )
        assertTrue(
            "the recheck must compare against the captured credential " +
                "(round 8 literal: the cookie road compares the session " +
                "GENERATION — rotation replaces the instance for the " +
                "same account, so the round-7 `===` form read a healthy " +
                "session as stale; the pin's ordering claim is unchanged)",
            body.contains("container.currentBearerToken() == fetchBearer") &&
                body.contains(
                    "container.sessionManager.sessionGeneration() == fetchSessionGeneration",
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
