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
    fun homeDegradesToThePublicFeedUnderTheHonestNote() {
        val home = mainSource("java/dev/cyberdogs/offleash/ui/home/HomeScreen.kt")
        assertTrue(
            "the degradation note copy is pinned",
            home.contains(
                "\"Your personalized feed was refused — showing the public feed.\"",
            ),
        )
        assertTrue(
            "the personalized browse carries the delegation",
            home.contains("client.homeFeedOAuth(bearer, chip, delegationProvider())"),
        )
        assertEquals(
            "the anonymous home feed is the fallback AND the signed-out road",
            2,
            home.split("client.homeFeed(auth, chip)").size - 1,
        )
        assertTrue(
            "the note state is rendered and published with its content",
            home.contains("val feedNote by viewModel.feedNote.collectAsState()") &&
                home.contains("if (feedNote != null)"),
        )
        assertTrue(
            "the screen wires the container's delegation provider",
            home.contains("delegationProvider = { container.currentBrowseDelegation() }"),
        )
    }

    @Test
    fun playlistPagesDegradeWithTheLikedShellGuarded() {
        val detail = mainSource("java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt")
        assertTrue(
            "the token playlist browse carries the delegation",
            detail.contains("delegationProvider()"),
        )
        assertTrue(
            "an empty anonymous Liked fallback rethrows the refusal",
            detail.contains(
                "if (browseId == \"VLLM\" && fallback.tracks.isEmpty()) throw e",
            ),
        )
        assertTrue(
            "continuation paging carries the delegation too",
            detail.contains(
                "client.playlistContinuationOAuth(bearer, token, delegationProvider())",
            ),
        )
        assertTrue(
            "the screen wires the container's delegation provider",
            detail.contains("delegationProvider = { container.currentBrowseDelegation() }"),
        )
    }

    @Test
    fun libraryBrowseCarriesTheDelegation() {
        val library = mainSource("java/dev/cyberdogs/offleash/ui/library/LibraryScreen.kt")
        assertTrue(
            library.contains("delegation = container.currentBrowseDelegation()"),
        )
    }

    @Test
    fun refusalCopyDistinguishesClasses() {
        val errors = mainSource("java/dev/cyberdogs/offleash/ui/ErrorMessages.kt")
        assertTrue(
            "401 reads as a refused sign-in with the step the user can take",
            errors.contains(
                "\"YouTube refused the app's sign-in for this — " +
                    "sign out and back in, then try again.\"",
            ) || errors.contains(
                "YouTube refused the app's sign-in for this — sign out and back in, then try again.",
            ),
        )
        assertTrue(
            "403 reads as a refused account request",
            errors.contains("\"YouTube refused this request for your account.\""),
        )
        assertTrue(
            "the original line still covers the unclassified refusal",
            errors.contains(
                "\"YouTube refused the app's request — the app may need an update.\"",
            ),
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
        assertTrue(
            "the container exposes the session's browse delegation",
            app.contains("fun currentBrowseDelegation(): BrowseDelegation?"),
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
