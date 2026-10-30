package dev.cyberdogs.offleash.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Library downloads wiring (bug sweep round 1, integration): the
 * download store's corruption flag gained its reader, and the
 * manager's clear-all gained its button — both pinned by source
 * shape because the screen is Compose (CI compiles it). The store
 * and manager behaviours themselves are unit-tested in
 * DownloadsTest / DownloadManagerTest.
 */
class LibraryDownloadsWiringContractTest {

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
                "$relativePath not found from " + File(".").absolutePath
            )
        return found.readText()
    }

    private fun libraryScreen(): String =
        mainSource("java/dev/cyberdogs/offleash/ui/library/LibraryScreen.kt")

    @Test
    fun corruptionFlagHasAVisibleReader() {
        assertTrue(libraryScreen().contains("container.downloadStore.loadRecoveredFromCorruption"))
        // Round 4: the notice must not claim the list "was started
        // fresh" — since the round-2/3 salvage the flag also fires
        // for a single dropped entry with every other download
        // intact, and the old text told the owner those surviving
        // downloads were gone. The wording has to cover a partial
        // loss honestly.
        assertTrue(libraryScreen().contains("couldn't be read completely"))
        assertTrue(libraryScreen().contains("some downloads may be missing"))
    }

    @Test
    fun clearAllIsWiredBehindAConfirmation() {
        val screen = libraryScreen()
        assertTrue(screen.contains("container.downloadManager.deleteAll()"))
        assertTrue(screen.contains("Clear all downloads"))
    }
}
