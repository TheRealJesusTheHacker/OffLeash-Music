package dev.cyberdogs.offleash.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 4 fresh-eyes sweep (the confirmation round before 0.8.0):
 * the defects this round found were mostly TWINS of defects earlier
 * rounds fixed in one place but not its sibling — plus one crash
 * and one false privacy claim. The screens are Compose
 * (CI-compiled only), so — in the house contract style — these
 * assertions pin the exact source shapes the fixes landed:
 *
 * - Settings still rendered "Session: checking…" for UNKNOWN
 *   health — the label round 2 removed from About and Diagnostics
 *   because UNKNOWN persists after a failed check; no check is
 *   running for most of that state's life.
 * - Settings' sign-out dialog still had an unbounded plain-Text
 *   body — the shape round 2 fixed in About because at 2.0x font
 *   on a short screen it pushes Sign out / Cancel off the dialog.
 * - The diagnostics intro claimed "No addresses or personal data
 *   are shown here" while the same screen renders (and its Share
 *   button sends) the signed-in account's name/email.
 * - LocalPlaylistScreen keyed its rows on videoId alone, but the
 *   store lets the same track be added twice — opening such a
 *   playlist threw on the duplicate LazyColumn key.
 * - Album/playlist pages rendered enabled Play all / Shuffle over
 *   an empty track list; the taps silently did nothing.
 * - The tree still carried the pre-release version name and docs
 *   describing the retired WebView sign-in and the pre-honesty
 *   §6 error shapes as current.
 */
class R4FreshEyesContractTest {

    @Test
    fun settingsHasNoSessionLabelToMisclaimWith() {
        // Re-based 0.9.0 (owner order, 2026-11-04): the session
        // label this pin policed is deleted with the Settings
        // Account block — there is no session state to label
        // honestly or otherwise.
        val settings = mainSource("java/dev/cyberdogs/offleash/ui/settings/SettingsScreen.kt")
        assertFalse(
            "Settings must not render any session label",
            settings.contains("Session:"),
        )
        assertFalse(
            "Settings must not consult account state",
            settings.contains("accountUiState"),
        )
    }

    @Test
    fun settingsHasNoSignOutDialogToOverflow() {
        // Re-based 0.9.0 (owner order, 2026-11-04): the sign-out
        // dialog is deleted with the Settings Account block.
        val settings = mainSource("java/dev/cyberdogs/offleash/ui/settings/SettingsScreen.kt")
        assertFalse(
            "Settings must not offer sign-out",
            settings.contains("Sign out"),
        )
    }

    @Test
    fun diagnosticsIntroMatchesTheAccountFreeScreen() {
        // Re-based 0.9.0 (owner order, 2026-11-04): the screen
        // shows and shares no account identity — its account
        // block is deleted — so the intro's claim is the plain
        // one, and the old disclosure wording must not survive.
        val screen = mainSource("java/dev/cyberdogs/offleash/ui/about/PlaybackDiagnosticsScreen.kt")
        assertFalse(
            "the old account-disclosure wording must not survive",
            screen.contains("Share diagnostics sends that line too"),
        )
        assertFalse(
            "the screen must not render an account block",
            screen.contains("Account & session"),
        )
        assertTrue(
            "the intro must say nothing on the screen is personal",
            screen.contains("Nothing here is personal"),
        )
    }

    @Test
    fun localPlaylistRowsStayKeyedUniquelyWhenATrackWasAddedTwice() {
        val library = mainSource("java/dev/cyberdogs/offleash/ui/library/LibraryScreen.kt")
        assertFalse(
            "a playlist can hold the same track twice; keying rows on videoId " +
                "alone crashes the screen on the duplicate key",
            library.contains("key = { _, track -> track.videoId }"),
        )
        assertTrue(
            "the row key must include the index so duplicates stay unique",
            library.contains("\$index:\${track.videoId}"),
        )
    }

    @Test
    fun detailPlayAllAndShuffleDisableWhenThePageHasNoTracks() {
        val detail = mainSource("java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt")
        assertTrue(
            "the header's buttons must take an enabled state",
            detail.contains("enabled = actionsEnabled"),
        )
        assertTrue(
            "both the album and the playlist call sites must disable the " +
                "actions when their track list is empty",
            detail.split("actionsEnabled = page.tracks.isNotEmpty()").size - 1 == 2,
        )
    }

    @Test
    fun shippedVersionIsTheReleaseThisSweepShips() {
        val gradle = repoFile("app/build.gradle.kts")
        assertTrue(
            "About renders the package versionName — the ship must " +
                "label itself with its own release name " +
                "(base \"0.9.0\" + the hunt flavor's \"-hunt1\" suffix = " +
                "the shipped 0.9.0-hunt1; the pin was re-based from " +
                "0.8.8-sweep at the 0.9.0 account-free ship, owner " +
                "order 2026-11-04)",
            gradle.contains("versionName = \"0.9.0\""),
        )
        assertFalse(
            "the pre-release version name must not survive into the ship build",
            gradle.contains("0.7.1-signinfix"),
        )
    }

    @Test
    fun docsDoNotDescribeTheRetiredSignInOrErrorShapesAsCurrent() {
        val readme = repoFile("README.md")
        assertFalse(
            "sign-in shipped in Phase 2 — the README must not say there is none",
            readme.contains("No account sign-in"),
        )
        assertFalse(
            "the README's suite counts must not be the Phase 1 numbers",
            readme.contains("(31 tests"),
        )
        val spec = repoFile("docs/SPEC.md")
        assertFalse(
            "user copy never names the raw player error code (round-3 honesty rule)",
            spec.contains("the code is always named"),
        )
        assertTrue(
            "SPEC §10 must carry the device-code supersession note",
            spec.contains("device-code flow"),
        )
        val roadmap = repoFile("docs/ROADMAP.md")
        assertTrue(
            "the ROADMAP Phase 2 section must carry the device-code supersession note",
            roadmap.contains("device-code flow"),
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

    /** Reads a file at the repository root (or relative to it). */
    private fun repoFile(relativePath: String): String {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, relativePath)
            candidates += File(dir, "src/$relativePath")
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
