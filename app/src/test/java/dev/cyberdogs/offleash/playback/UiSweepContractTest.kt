package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/**
 * Source contracts for the UI sweep (lane 7/8): the defects these
 * pin were all Compose-layer and cannot run on this JVM suite, in
 * the repo's established contract-test style (see
 * SearchStaleResultContractTest). Each test names the defect it
 * keeps from coming back.
 */
class UiSweepContractTest {

    private fun locate(relativeFromAppModule: String): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, "app/src/main/java/$relativeFromAppModule")
            candidates += File(dir, "src/app/src/main/java/$relativeFromAppModule")
            candidates += File(dir, "src/main/java/$relativeFromAppModule")
            dir = dir.parentFile
            hops++
        }
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "not found from " + File(".").absolutePath + ": " + relativeFromAppModule,
            )
    }

    private fun source(relativeFromAppModule: String): String =
        locate(relativeFromAppModule).readText()

    private val errorMessages by lazy {
        source("dev/cyberdogs/offleash/ui/ErrorMessages.kt")
    }

    // -- Error copy: no raw codes or server wording in user copy --

    @Test
    fun playerFallbackCarriesNoRawErrorCode() {
        // Owner screenshot, 2026-10-27: a card ending
        // "(player error 2000)". The code belongs in diagnostics.
        assertTrue(
            "playerMessage fallback must not interpolate the raw error code",
            !errorMessages.contains("player error \$errorCode"),
        )
        assertTrue(
            "playerMessage fallback must point at the diagnostics screen",
            errorMessages.contains("About → Playback diagnostics"),
        )
    }

    @Test
    fun userMessageNeverInterpolatesRawServerWording() {
        assertTrue(
            "userMessage must not interpolate either road's exception message",
            !errorMessages.contains("error.accountError.message") &&
                !errorMessages.contains("error.anonymousError.message"),
        )
        assertTrue(
            "userMessage must not quote the server's raw message",
            !errorMessages.contains("\${error.serverMessage}"),
        )
    }

    // -- Theme: every role set, every text pair at WCAG 4.5:1 --

    private fun themeColors(): Map<String, String> {
        val theme = source("dev/cyberdogs/offleash/ui/theme/Theme.kt")
        val regex = Regex("""(\w+)\s*=\s*Color\(0xFF([0-9A-Fa-f]{6})\)""")
        return regex.findAll(theme).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun luminance(hex: String): Double {
        fun channel(i: Int): Double {
            val c = hex.substring(i, i + 2).toInt(16) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4)
    }

    private fun contrast(a: String, b: String): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun themeSetsTheContainerRolesSoNothingFallsBackToBaselinePurple() {
        val colors = themeColors()
        for (role in listOf(
            "primaryContainer", "onPrimaryContainer",
            "secondaryContainer", "onSecondaryContainer",
            "tertiaryContainer", "onTertiaryContainer",
            "errorContainer", "onErrorContainer", "outline",
        )) {
            assertTrue(
                "Theme.kt must set $role — unset roles render Material's " +
                    "baseline palette (its dark secondaryContainer is purple-grey)",
                colors.containsKey(role),
            )
        }
    }

    @Test
    fun themeTextPairsMeetContrast() {
        val colors = themeColors()
        val pairs = listOf(
            "onBackground" to "background",
            "onSurface" to "surface",
            "onSurfaceVariant" to "surface",
            "onSurfaceVariant" to "surfaceVariant",
            "onSurfaceVariant" to "background",
            "primary" to "background",
            "primary" to "surface",
            "onPrimary" to "primary",
            "onSecondary" to "secondary",
            "onPrimaryContainer" to "primaryContainer",
            "onSecondaryContainer" to "secondaryContainer",
            "onTertiaryContainer" to "tertiaryContainer",
            // error does double duty: text on the dark backgrounds
            // AND the player banner's background under onError.
            "error" to "background",
            "error" to "surface",
            "onError" to "error",
            "onErrorContainer" to "errorContainer",
        )
        for ((fg, bg) in pairs) {
            val ratio = contrast(colors.getValue(fg), colors.getValue(bg))
            assertTrue(
                "$fg on $bg is ${"%.2f".format(ratio)}:1 — normal text needs 4.5:1",
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun noPurpleColorLiteralAnywhereInTheUi() {
        val uiDir = locate("dev/cyberdogs/offleash/ui/theme/Theme.kt").parentFile.parentFile
        val literal = Regex("""Color\(0xFF([0-9A-Fa-f]{6})\)""")
        uiDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            literal.findAll(file.readText()).forEach { match ->
                val hex = match.groupValues[1]
                val r = hex.substring(0, 2).toInt(16)
                val g = hex.substring(2, 4).toInt(16)
                val b = hex.substring(4, 6).toInt(16)
                assertTrue(
                    "${file.name}: 0xFF$hex is violet (blue highest, red over green) — " +
                        "the house palette is black + tan, no purple anywhere",
                    !(b > r && r > g),
                )
            }
        }
    }

    // -- App shell: back closes the player before it navigates --

    @Test
    fun rootHandlesBackForTheExpandedPlayer() {
        val root = source("dev/cyberdogs/offleash/ui/OffleashRoot.kt")
        assertTrue(
            "OffleashRoot must give the expanded player a BackHandler — " +
                "the player is an overlay, so without one back pops the " +
                "route underneath it (or leaves the app) while it stays open",
            root.contains("BackHandler(enabled = playerExpanded)"),
        )
    }

    // -- H5 (0.6.6 standard): shared panels stay reachable --

    @Test
    fun errorPanelAndPlaylistPickerScroll() {
        val common = source("dev/cyberdogs/offleash/ui/components/Common.kt")
        // Round 12 presence guards (meta-audit): each panel's
        // slice, widened by a missing anchor, reaches the OTHER
        // panel's verticalScroll (and a heightIn above the
        // picker), so both halves passed hollow on anchor
        // deletion alone.
        assertTrue("ErrorPanel must exist at all", common.contains("fun ErrorPanel"))
        assertTrue("PlaylistPickerDialog must exist at all", common.contains("fun PlaylistPickerDialog"))
        val errorPanel = common
            .substringAfter("fun ErrorPanel")
            .substringBefore("fun rememberFeedActions")
        assertTrue(
            "ErrorPanel must scroll — a long error in a short viewport " +
                "clipped its own Try again button",
            errorPanel.contains("verticalScroll"),
        )
        val picker = common
            .substringAfter("fun PlaylistPickerDialog")
            .substringBefore("private fun FeedCard")
        assertTrue(
            "PlaylistPickerDialog's list must be height-bounded and scroll — " +
                "a dialog's text area does not scroll on its own",
            picker.contains("verticalScroll") && picker.contains("heightIn"),
        )
    }

    // -- Track menu: the Download item names the live state --

    @Test
    fun trackMenuDownloadItemIsStateAware() {
        val menu = source("dev/cyberdogs/offleash/ui/components/TrackMenu.kt")
        assertTrue(
            "the Download item must render the downloaded state, not a live " +
                "\"Download\" tap that silently no-ops",
            menu.contains("\"Downloaded\"") && menu.contains("DownloadState.DONE"),
        )
        assertTrue(
            "a failed download must offer its retry in the menu",
            menu.contains("\"Retry download\""),
        )
    }

    // -- Diagnostics screen: share exists, test playback can't double-run --

    @Test
    fun diagnosticsScreenSharesAndGuardsTestPlayback() {
        val screen = source("dev/cyberdogs/offleash/ui/about/PlaybackDiagnosticsScreen.kt")
        assertTrue(
            "the diagnostics screen must offer Share — its own instructions " +
                "tell the user to send this text onward",
            screen.contains("\"Share diagnostics\""),
        )
        assertTrue(
            "Test playback must be guarded against a second tap while a " +
                "run is in flight",
            screen.contains("testRunning") && screen.contains("enabled = !testRunning"),
        )
    }
}
