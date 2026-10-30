package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Round 47 sweep contracts (confirmation round — breaking round
 * 46's fixes and exhausting its field census's remainder):
 *
 * (1) The R46 publish pin counts each ViewModel's publish
 * expression once in its load slice and bans a list of
 * transformation spellings — but it never bound the publish
 * GUARD, and its ban list has no `.asReversed()`. Proven live
 * this round (full app suite green, 596): ChartsViewModel kept
 * its needle verbatim, then re-published
 * `UiState.Content(it.data.asReversed())` immediately after
 * `_state.value = result` inside the guard — the Charts page
 * rendered upside down while every R46 assertion passed. The
 * guard blocks are therefore bound verbatim below for every
 * simple ViewModel, and `.asReversed()` joins the banned
 * spellings in every publish slice. (Search's and Home's guards
 * are bound too: their A1-shape doctors were demonstrated shut
 * this round by R19's construction count and R30's `.copy(`
 * count respectively, and the verbatim guard keeps them so.)
 *
 * (2) The unlisted state holders. R46 listed six ViewModels;
 * three more publishes were bound nowhere: LibraryScreen's
 * account-playlists state (a composable `remember` state, not a
 * ViewModel — proven green: `UiState.Content(loaded.asReversed())`
 * rendered the account library backwards), PlaylistViewModel's
 * loadMore merge publish, and SearchViewModel's loadMore merge
 * publish. The loadMore CALLS were likewise unbound — proven
 * green: both playlist continuation calls keyed on `browseId`
 * instead of the tracker token, and `searchContinuation` keyed
 * on the query instead of the token, while the R5 pins (which
 * bind only tryAdvance) still passed. All are bound below.
 * (Demonstrated shut this round, for the record:
 * LocalPlaylistStore's load publish — reversing it at
 * `_playlists.value = readFromDisk()` failed exactly the two
 * runtime order pins in StoresTest and LongHaulSweepTest, so
 * the local library list has no unbound publish.)
 *
 * (3) The mood route joint. Core pins now bind every mood
 * category's params (round 47 core), and SyncFreshness binds
 * MoodPlaylistsScreen's category reconstruction — but the
 * route construction in OffleashRoot was read by no pin:
 * swapping params and title in the navigate() call ran green
 * (596) while every mood tap opened its page under the title
 * as params (a browse that can only fail or return the wrong
 * surface). Bound verbatim below, crossed form banned.
 *
 * (4) SectionRow's card type routing. R44 binds SectionRow's
 * track queueing, the factory, and each card branch's click
 * line as a whole-slice count — but the `cards` partition line
 * was unbound, so remapping the card items album<->artist
 * above the `when` (branch text verbatim, every counted needle
 * intact) ran green (596) while every album card opened the
 * artist route and vice versa. The partition line is bound
 * verbatim (newline-terminated), each branch is sliced and
 * bound to its own destination only, and the FeedItem type
 * mentions in the row are counted exactly — a remap
 * construction necessarily adds one.
 *
 * The ViewModel/UI files are Compose-bound, so the shape is
 * the pin (house style; CI is the compile gate).
 */
class R47SweepContractTest {

    @Test
    fun everyPublishGuardIsVerbatimAndNoPublishSliceReverses() {
        val guard = "            if (isActive) {\n" +
            "                _state.value = result\n" +
            "            }"
        val simple = listOf(
            Triple(
                "java/dev/cyberdogs/offleash/ui/home/ChartsScreen.kt",
                "class ChartsViewModel", "@Composable",
            ),
            Triple(
                "java/dev/cyberdogs/offleash/ui/home/MoodsScreen.kt",
                "class MoodsViewModel", "@Composable",
            ),
            Triple(
                "java/dev/cyberdogs/offleash/ui/home/MoodsScreen.kt",
                "class MoodPlaylistsViewModel", "@Composable",
            ),
            Triple(
                "java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt",
                "class AlbumViewModel", "@Composable",
            ),
            Triple(
                "java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt",
                "class ArtistViewModel", "@Composable",
            ),
        )
        for ((path, from, until) in simple) {
            val body = mainSource(path).substringAfter(from).substringBefore(until)
            assertEquals(
                "$path's $from must publish its result under the " +
                    "shipped guard verbatim — a second, transformed " +
                    "republication inside the guard (the round-47 " +
                    "asReversed doctor) changes what renders while " +
                    "the R46 publish needle still counts once",
                1,
                occurrences(body, guard),
            )
            assertEquals(
                "$path's $from load body must hold no asReversed " +
                    "transformation of the published list",
                0,
                occurrences(body, ".asReversed()"),
            )
        }
        // The two guarded publishes that also start a tracker:
        // Playlist load and Search search share this guard shape.
        val trackerGuard = "            if (isActive) {\n" +
            "                fetched?.let { continuationTracker.start(it.continuation) }\n" +
            "                _state.value = result\n" +
            "                _hasMore.value = continuationTracker.current != null\n" +
            "            }"
        val playlistBody = mainSource(
            "java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt",
        ).substringAfter("class PlaylistViewModel").substringBefore("fun loadMore")
        assertEquals(
            "PlaylistViewModel must publish under its shipped guard verbatim",
            1,
            occurrences(playlistBody, trackerGuard),
        )
        assertEquals(
            "PlaylistViewModel's load body must hold no asReversed transformation",
            0,
            occurrences(playlistBody, ".asReversed()"),
        )
        val searchBody = mainSource(
            "java/dev/cyberdogs/offleash/ui/search/SearchScreen.kt",
        ).substringAfter("fun search(query: String").substringBefore("fun loadMore()")
        assertEquals(
            "SearchViewModel.search must publish under its shipped guard verbatim",
            1,
            occurrences(searchBody, trackerGuard),
        )
        assertEquals(
            "SearchViewModel.search's body must hold no asReversed transformation",
            0,
            occurrences(searchBody, ".asReversed()"),
        )
    }

    @Test
    fun libraryAndLoadMorePublishesAndCallsAreBound() {
        val library = mainSource(
            "java/dev/cyberdogs/offleash/ui/library/LibraryScreen.kt",
        ).substringAfter("LaunchedEffect(sessionState.signedIn")
            .substringBefore("LazyColumn(Modifier.fillMaxSize())")
        assertEquals(
            "LibraryScreen must publish the loaded account playlists " +
                "untransformed — `UiState.Content(loaded)` inside its " +
                "shipped if/else exactly once; this state holder is a " +
                "composable remember state, listed by no R46 case",
            1,
            occurrences(
                library,
                "            if (loaded == null) {\n" +
                    "                UiState.Error(\"Sign in again to load your YouTube library.\")\n" +
                    "            } else {\n" +
                    "                UiState.Content(loaded)\n" +
                    "            }",
            ),
        )
        assertEquals(
            "LibraryScreen's load effect must hold no reversal of the loaded list",
            0,
            occurrences(library, ".asReversed()") + occurrences(library, ".reversed()"),
        )

        val detail = mainSource(
            "java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt",
        )
        val playlistMore = detail.substringAfter("class PlaylistViewModel")
            .substringAfter("fun loadMore()")
            .substringBefore("@Composable")
        assertEquals(
            "playlist loadMore must fetch its next chunk with the " +
                "tracker token on the token road — keying the call " +
                "on browseId pages a different identity while the " +
                "R5 tryAdvance pin still passes",
            1,
            occurrences(playlistMore, "client.playlistContinuationOAuth(bearer, token, delegationProvider())"),
        )
        assertEquals(
            "playlist loadMore must fetch its next chunk with the tracker token on the cookie road",
            1,
            occurrences(playlistMore, "client.playlistContinuation(token, auth = authProvider())"),
        )
        assertEquals(
            "playlist loadMore must never key a continuation call on the page id",
            0,
            occurrences(playlistMore, "playlistContinuation(browseId") +
                occurrences(playlistMore, "playlistContinuationOAuth(bearer, browseId"),
        )
        assertEquals(
            "playlist loadMore must append the chunk's fresh tracks in order",
            1,
            occurrences(playlistMore, "tracks = latest.tracks + fresh,"),
        )
        assertEquals(
            "playlist loadMore's merge must hold no reversal of the appended chunk",
            0,
            occurrences(playlistMore, ".asReversed()"),
        )

        val search = mainSource(
            "java/dev/cyberdogs/offleash/ui/search/SearchScreen.kt",
        )
        val searchMore = search.substringAfter("fun loadMore()")
            .substringBefore("fun retry()")
        assertEquals(
            "search loadMore must fetch its next chunk with the tracker token",
            1,
            occurrences(searchMore, "val chunk = client.searchContinuation(token)"),
        )
        assertEquals(
            "search loadMore must never key its continuation call on the query",
            0,
            occurrences(searchMore, "searchContinuation(current.query"),
        )
        assertEquals(
            "search loadMore must append the chunk's sections in order",
            1,
            occurrences(searchMore, "sections = latest.sections + chunk.sections,"),
        )
        assertEquals(
            "search loadMore's merge must hold no reversal of the appended sections",
            0,
            occurrences(searchMore, ".asReversed()"),
        )
    }

    @Test
    fun moodRouteBindsParamsThenTitle() {
        val root = mainSource("java/dev/cyberdogs/offleash/ui/OffleashRoot.kt")
        assertEquals(
            "the mood route must carry the category's params first " +
                "and its title second, exactly as the route pattern " +
                "\"mood/{params}/{title}\" declares — swapping them " +
                "opens every mood page under a title-as-params browse",
            1,
            occurrences(
                root,
                "\"mood/\${Uri.encode(category.params)}/\${Uri.encode(category.title)}\",",
            ),
        )
        assertEquals(
            "the mood route must never carry title as params",
            0,
            occurrences(
                root,
                "\"mood/\${Uri.encode(category.title)}/\${Uri.encode(category.params)}\",",
            ),
        )
    }

    @Test
    fun sectionRowCardRoutingBindsEachBranchToItsOwnDestination() {
        val common = mainSource("java/dev/cyberdogs/offleash/ui/components/Common.kt")
        val row = common.substringAfter("fun SectionRow(").substringBefore("fun ErrorPanel")
        assertEquals(
            "SectionRow's card partition must be the shipped line " +
                "verbatim (newline-terminated, so a suffixed remap " +
                "cannot survive as its prefix) — remapping card items " +
                "across types above the `when` reroutes every card " +
                "while each branch's counted click line stays intact",
            1,
            occurrences(
                row,
                "    val cards = section.items.filterNot { it is FeedItem.OfTrack }\n",
            ),
        )
        for (type in listOf("FeedItem.OfAlbum", "FeedItem.OfArtist", "FeedItem.OfPlaylist")) {
            assertEquals(
                "SectionRow must mention $type exactly once (its own " +
                    "branch) — a type-remapping construction adds a mention",
                1,
                occurrences(row, type),
            )
        }
        val albumClick = "onClick = { actions.onAlbumClick(item.album.browseId) }"
        val artistClick = "onClick = { actions.onArtistClick(item.artist.browseId) }"
        val playlistClick = "onClick = { actions.onPlaylistClick(item.playlist.browseId, null) }"
        val albumBranch = row.substringAfter("is FeedItem.OfAlbum ->")
            .substringBefore("is FeedItem.OfArtist ->")
        val artistBranch = row.substringAfter("is FeedItem.OfArtist ->")
            .substringBefore("is FeedItem.OfPlaylist ->")
        val playlistBranch = row.substringAfter("is FeedItem.OfPlaylist ->")
            .substringBefore("is FeedItem.OfTrack ->")
        assertEquals("the album branch must report to the album destination only",
            1, occurrences(albumBranch, albumClick))
        assertEquals("the album branch must hold no other destination",
            0, occurrences(albumBranch, artistClick) + occurrences(albumBranch, playlistClick))
        assertEquals("the artist branch must report to the artist destination only",
            1, occurrences(artistBranch, artistClick))
        assertEquals("the artist branch must hold no other destination",
            0, occurrences(artistBranch, albumClick) + occurrences(artistBranch, playlistClick))
        assertEquals("the playlist branch must report to the playlist destination only",
            1, occurrences(playlistBranch, playlistClick))
        assertEquals("the playlist branch must hold no other destination",
            0, occurrences(playlistBranch, albumClick) + occurrences(playlistBranch, artistClick))
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
