package dev.cyberdogs.offleash.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 5 sweep (the second confirmation round before 0.8.0):
 * again the findings were twins of earlier rounds' fixes that had
 * not spread — the case-insensitivity that stopped at the sibling
 * suffix, the duplicate-row support that stopped at the row key,
 * the honest state label that stopped at the sibling surface, and
 * the bounded dialog body that stopped at the sibling dialog. The
 * screens are Compose (CI-compiled only), so — in the house
 * contract style — these assertions pin the exact source shapes
 * the fixes landed. (The store/manager halves of this round's
 * findings are pinned by real JVM tests in DownloadsTest,
 * DownloadManagerTest and StoresTest.)
 */
class R5SweepContractTest {

    @Test
    fun trackMenuNeverCallsAQueuedDownloadDownloading() {
        val menu = mainSource("java/dev/cyberdogs/offleash/ui/components/TrackMenu.kt")
        assertTrue(
            "a QUEUED track is waiting behind the one-at-a-time engine; the " +
                "menu must use the Library's honest label for that state",
            menu.contains("DownloadState.QUEUED -> DropdownMenuItem") &&
                menu.contains("Text(\"Waiting to download\")"),
        )
        assertFalse(
            "QUEUED and DOWNLOADING must not share the \"Downloading…\" label",
            menu.contains("DownloadState.QUEUED,\n        dev.cyberdogs.offleash.data.DownloadState.DOWNLOADING"),
        )
    }

    @Test
    fun localPlaylistRowDeleteRemovesThatRowNotEveryCopyOfTheTrack() {
        val library = mainSource("java/dev/cyberdogs/offleash/ui/library/LibraryScreen.kt")
        assertTrue(
            "the row delete must remove by index (removeTrackAt) — the same " +
                "track may appear twice and remove-by-id deleted every copy",
            library.contains("localPlaylistStore.removeTrackAt("),
        )
    }

    @Test
    fun deletePlaylistDialogTextScrollsSoItsButtonsSurviveLargeFonts() {
        val library = mainSource("java/dev/cyberdogs/offleash/ui/library/LibraryScreen.kt")
        val dialog = library.substringAfter("Text(\"Delete playlist?\")")
            .substringBefore("showClearDownloads")
        assertTrue(
            "the delete-playlist dialog embeds the uncapped playlist name in " +
                "its body — it must be height-bounded and scrollable like the " +
                "sign-out dialogs fixed in rounds 2/4",
            dialog.contains("heightIn(max = 320.dp)") && dialog.contains("verticalScroll"),
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
