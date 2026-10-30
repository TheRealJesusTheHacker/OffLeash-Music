package dev.cyberdogs.offleash.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 6 sweep contract pins for the Compose-only surfaces this
 * round touched (CI compiles them; the harness cannot). The store
 * half of the playlist finding is pinned by real JVM tests in
 * StoresTest; this pins that the screen actually passes the row's
 * identity to the guarded removal, rather than the bare
 * render-time index whose shift used to delete the wrong track.
 */
class R6SweepContractTest {

    @Test
    fun localPlaylistRowDeletePassesTheRowsIdentityAsAStaleTapGuard() {
        // Literal updated in round 8 (purpose unchanged): the row
        // delete now passes the tapped row's full Track value
        // instead of its videoId — a videoId cannot tell duplicate
        // copies of one track apart (see R8PlaylistGuardTest). The
        // pin's claim stands: the delete passes the row's identity
        // to the identity-guarded removeTrackAt.
        val library = mainSource("java/dev/cyberdogs/offleash/ui/library/LibraryScreen.kt")
        // Round 12 presence guard (meta-audit): without it, a
        // missing call anchor widens the slice to the whole file
        // up to the first "track,", a prefix that already
        // contains both asserted literals elsewhere.
        assertTrue(
            "the removeTrackAt call must exist at all",
            library.contains("localPlaylistStore.removeTrackAt("),
        )
        val call = library.substringAfter("localPlaylistStore.removeTrackAt(")
            .substringBefore("track,")
        assertTrue(
            "the row delete must pass the tapped row's identity to " +
                "removeTrackAt so a list shift between render and the " +
                "async removal drops the stale tap instead of deleting " +
                "whatever track slid into the captured index",
            call.contains("playlist.id") && call.contains("index"),
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
