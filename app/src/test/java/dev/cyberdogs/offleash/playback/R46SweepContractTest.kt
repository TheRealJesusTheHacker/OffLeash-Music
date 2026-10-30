package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Round 46 sweep contracts (confirmation round — extending the
 * round-45 census from ORDER to FIELDS and to the app layer):
 *
 * ViewModel publish expressions. Home's loadFeed is span-bound
 * by the R30 pin and Search's Content construction is bound by
 * the R19 pin — proven again this round: reversing the sections
 * inside SearchViewModel's publish failed exactly the R19 test.
 * Every OTHER ViewModel's load body was read by pins that bind
 * other spans (stale guards, tracker placement, iteration
 * sources), and its publish expression was bound NOWHERE.
 * Proven live this round, each green across the full app suite
 * (593): the Charts sections reversed at the ChartsViewModel
 * publish; the moods list reversed at the MoodsViewModel
 * publish; the mood-category sections reversed at the
 * MoodPlaylistsViewModel publish; the album page's tracks
 * reversed at the AlbumViewModel publish; the artist page's
 * sections reversed at the ArtistViewModel publish; and the
 * playlist page's tracks reversed at the PlaylistViewModel
 * publish. (One honest note: the playlist construction's first
 * spelling, `page.copy(tracks = page.tracks.reversed())`,
 * failed R21's `tracks = page.tracks` count by substring
 * collision — the needle occurs inside the doctored text —
 * and the needle-avoiding spelling
 * `page.let { q -> q.copy(tracks = q.tracks.reversed()) }`
 * ran green, so the publish itself was never bound.)
 *
 * This pin binds each of the six publish expressions verbatim
 * (count exactly one in its ViewModel's slice — a transformed
 * publish changes the needle text and the count falls to zero)
 * and bans transformation calls inside those slices.
 *
 * The ViewModel files are Compose/lifecycle-bound, so the shape
 * is the pin (house style; CI is the compile gate).
 */
class R46SweepContractTest {

    @Test
    fun everyViewModelPublishesTheClientsReturnUntransformed() {
        val cases = listOf(
            Triple(
                "java/dev/cyberdogs/offleash/ui/home/ChartsScreen.kt",
                "class ChartsViewModel" to "@Composable",
                "UiState.Content(client.charts())",
            ),
            Triple(
                "java/dev/cyberdogs/offleash/ui/home/MoodsScreen.kt",
                "class MoodsViewModel" to "@Composable",
                "UiState.Content(client.moods())",
            ),
            Triple(
                "java/dev/cyberdogs/offleash/ui/home/MoodsScreen.kt",
                "class MoodPlaylistsViewModel" to "@Composable",
                "UiState.Content(client.moodPlaylists(category))",
            ),
            Triple(
                "java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt",
                "class AlbumViewModel" to "@Composable",
                "UiState.Content(client.album(browseId))",
            ),
            Triple(
                "java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt",
                "class ArtistViewModel" to "@Composable",
                "UiState.Content(client.artist(browseId))",
            ),
            Triple(
                "java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt",
                "class PlaylistViewModel" to "fun loadMore",
                "UiState.Content(page)",
            ),
        )
        for ((path, slice, publish) in cases) {
            val text = mainSource(path)
            val body = text
                .substringAfter(slice.first)
                .substringBefore(slice.second)
            assertEquals(
                "$path's ${slice.first} must publish the client's " +
                    "return untransformed — `$publish` exactly once " +
                    "in its load body; a transformed publish (reversed, " +
                    "sorted, deduped, dropped) renders and queues a " +
                    "different list than the client returned while " +
                    "every core pin on that return still passes",
                1,
                occurrences(body, publish),
            )
            for (transformed in listOf(
                ".reversed()", ".sortedBy", ".distinctBy",
                ".drop(", ".dropLast(", ".shuffled()",
            )) {
                assertEquals(
                    "$path's ${slice.first} load body must hold no " +
                        "transformation of the published list (`$transformed`)",
                    0,
                    occurrences(body, transformed),
                )
            }
        }
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

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
