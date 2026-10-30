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
    fun settingsSessionLabelNeverClaimsAnActiveCheckThatIsNotRunning() {
        val settings = mainSource("java/dev/cyberdogs/offleash/ui/settings/SettingsScreen.kt")
        assertFalse(
            "UNKNOWN health persists after a failed check — Settings must not " +
                "claim a check is running (About and Diagnostics were fixed in round 2)",
            settings.contains("Session: checking"),
        )
        assertTrue(
            "Settings must use the same honest resting label as About",
            settings.contains("Session: not confirmed yet"),
        )
    }

    @Test
    fun settingsSignOutDialogTextScrollsSoItsButtonsSurviveLargeFonts() {
        val settings = mainSource("java/dev/cyberdogs/offleash/ui/settings/SettingsScreen.kt")
        val dialog = settings.substringAfter("Text(\"Sign out?\")")
        assertTrue(
            "the sign-out dialog's text must be height-bounded and scrollable — " +
                "at 2.0x font its buttons were pushed past the dialog's edge",
            dialog.contains("heightIn(max = 320.dp)") && dialog.contains("verticalScroll"),
        )
    }

    @Test
    fun diagnosticsIntroDisclosesTheAccountIdentityTheScreenShowsAndShares() {
        val screen = mainSource("java/dev/cyberdogs/offleash/ui/about/PlaybackDiagnosticsScreen.kt")
        assertFalse(
            "the screen renders (and shares) the account's name/email — " +
                "the intro must not claim no personal data is shown",
            screen.contains("No addresses or personal data are shown here"),
        )
        assertTrue(
            "the intro must disclose that the account block is personal and shared",
            // Round 6 (F6) corrected the wording pinned here: the
            // block renders ONE identity line (name ?: email), so
            // the intro now says "name or email" and "sends that
            // line too" — this assertion's purpose (the intro
            // discloses the share) is unchanged; its literal aged.
            screen.contains("Share diagnostics sends that line too"),
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
            "About renders the package versionName — the 0.8.0 ship must not " +
                "label itself with the pre-release sign-in-fix name " +
                "(coordinator naming: base \"0.8.0-sweep\" + the hunt flavor's " +
                "\"-hunt1\" suffix = the shipped 0.8.0-sweep-hunt1)",
            gradle.contains("versionName = \"0.8.0-sweep\""),
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
