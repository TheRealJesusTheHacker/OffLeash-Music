package dev.cyberdogs.offleash.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 0.8.2 browse hunt's app-side wiring, pinned at the source —
 * the Compose layer compiles only in CI, so these censuses are what
 * keep the degradation + delegation wiring from silently rotting:
 *
 * - Home degrades: a refused personalized feed falls back to the
 *   anonymous feed under the honest note (never a blank + error),
 *   and the delegation provider is wired into the OAuth browse.
 * - Playlist pages degrade the same way for public playlists, with
 *   the one dishonest shape (the anonymous Liked shell) guarded.
 * - Library passes the delegation into its account browse.
 * - Refusal copy distinguishes classes (401 sign-in / 403 account /
 *   the original update line for the rest).
 * - Browse attempts land in the playback diagnostics trail.
 * - Search never rides the token road (it stays on the anonymous
 *   WEB_REMIX search), so a browse refusal can never blank it.
 */
class BrowseHuntContractTest {

    @Test
    fun homeIsTheAnonymousFeedWithNoDegradationLayer() {
        // Re-based 0.9.0 (owner order, 2026-11-04): there is no
        // personalized feed to degrade FROM — the sign-in product
        // is removed, and Home is the anonymous feed, loaded once,
        // with no note and no delegation anywhere in the wiring.
        val home = mainSource("java/dev/cyberdogs/offleash/ui/home/HomeScreen.kt")
        assertEquals(
            "the anonymous home feed is the only home feed",
            1,
            home.split("client.homeFeed(null, chip)").size - 1,
        )
        assertFalse(
            "no personalized browse may be wired",
            home.contains("homeFeedOAuth"),
        )
        assertFalse(
            "no degradation note state may exist",
            home.contains("feedNote"),
        )
        assertFalse(
            "no delegation provider may be wired",
            home.contains("delegationProvider"),
        )
    }

    @Test
    fun playlistPagesLoadAnonymouslyWithNoLikedShell() {
        // Re-based 0.9.0 (owner order, 2026-11-04): playlist pages
        // load on the anonymous road only — no token cascade, no
        // delegation, and no Liked shell to guard, because the
        // account's Liked page is not a surface in this product.
        val detail = mainSource("java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt")
        assertTrue(
            "the playlist browse is the anonymous call",
            detail.contains("client.playlist(browseId, params, auth = null)"),
        )
        assertTrue(
            "continuation paging is anonymous too",
            detail.contains("client.playlistContinuation(token, auth = null)"),
        )
        assertFalse(
            "no delegation may be wired",
            detail.contains("delegationProvider"),
        )
        assertFalse(
            "no VLLM shell guard may exist",
            detail.contains("VLLM"),
        )
    }

    @Test
    fun libraryHasNoAccountBrowseToCarryAnything() {
        // Re-based 0.9.0 (owner order, 2026-11-04): the Library's
        // account browse is deleted with the sign-in product; the
        // Library is on-device content only.
        val library = mainSource("java/dev/cyberdogs/offleash/ui/library/LibraryScreen.kt")
        assertFalse(library.contains("currentBrowseDelegation"))
        assertFalse(library.contains("libraryPlaylists"))
    }

    @Test
    fun refusalCopyDistinguishesClasses() {
        // Re-based 0.9.0 (owner order, 2026-11-04): the classes
        // are still distinguished, but no copy may instruct a
        // sign-in that does not exist — 401 falls to the update
        // line and 403 names the refusal plainly.
        val errors = mainSource("java/dev/cyberdogs/offleash/ui/ErrorMessages.kt")
        assertTrue(
            "403 reads as a plain refusal",
            errors.contains("\"YouTube refused this request.\""),
        )
        assertTrue(
            "the update line covers 401 and the unclassified refusal",
            errors.contains(
                "\"YouTube refused the app's request — the app may need an update.\"",
            ),
        )
        assertFalse(
            "no copy may instruct signing in or out",
            errors.contains("sign out and back in") || errors.contains("sign-in for this"),
        )
    }

    @Test
    fun browseAttemptsLandInTheDiagnosticsTrail() {
        val app = mainSource("java/dev/cyberdogs/offleash/OffleashApp.kt")
        assertTrue(
            app.contains(
                "browseAttemptLog = { line -> playbackDiagnostics.log(\"browse\", line) }",
            ),
        )
        assertFalse(
            "the container exposes no browse delegation in the " +
                "account-free build",
            app.contains("currentBrowseDelegation"),
        )
    }

    @Test
    fun searchNeverRidesTheTokenRoad() {
        val search = mainSource("java/dev/cyberdogs/offleash/ui/search/SearchScreen.kt")
        assertFalse(
            "search must stay on the anonymous road — a browse refusal " +
                "can never blank it",
            search.contains("currentBearerToken"),
        )
        assertTrue(search.contains("client.search("))
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
