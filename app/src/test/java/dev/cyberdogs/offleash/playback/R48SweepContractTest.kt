package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Round 48 sweep contracts (confirmation round — breaking round
 * 47's fixes):
 *
 * (1) The publish census, completed semantically. R46 bound each
 * ViewModel publish by its needle plus a ban list of
 * transformation spellings; R47 added the guard verbatim and
 * banned `.asReversed()`. The ban list is a census that can
 * never be complete: this round, a republication AFTER the
 * verbatim guard using spellings no pin bans — `.take(1)` on
 * Charts, `.filter { ... }` on Moods, `.copy(tracks = ...
 * .take(1))` on Album, `.take(1)` after Library's bound
 * if/else, `.take(1)` after Search's bound load-more merge —
 * each ran green across the full app suite (600) while the
 * page rendered a narrowed list, and Playlist's load-more
 * `val fresh = newTracksOnly(...)` computation itself was
 * bound by no pin (its arguments swapped ran green: Load
 * more appended the first page's tracks again instead of the
 * chunk's). The fix is spelling-independent: every publish
 * slice must hold EXACTLY its shipped number of state
 * assignments and Content constructions, so any second
 * publish — under any spelling, in any position — breaks the
 * count. (The slices hold no `.update {`/`compareAndSet`
 * writes on the shipped tree; both are asserted at zero so
 * the assignment count stays the only write shape.)
 *
 * (2) The whole OffleashRoot route table. R47 bound the mood
 * route's navigate() call; no pin read any other registration.
 * This round, swapping the charts and moods destinations
 * (mood navigate line verbatim) ran green (600) — the Charts
 * tab would open the mood grid and vice versa — and swapping
 * the album route's onAlbumClick/onArtistClick handoffs ran
 * green. Every registration is therefore bound below as an
 * exact block (destination composable + argument handoffs),
 * together with the openAlbum/openArtist/openPlaylist
 * lambdas and the start destination.
 *
 * The ViewModel/UI files are Compose-bound, so the shape is
 * the pin (house style; CI is the compile gate).
 */
class R48SweepContractTest {

    @Test
    fun everyPublishSliceHoldsExactlyItsShippedPublishes() {
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
                "$path's $from must assign its state exactly twice " +
                    "(Loading, then the guarded result) — a transformed " +
                    "republication after the guard, under any spelling " +
                    "no ban list names, is a third assignment",
                2,
                occurrences(body, "_state.value ="),
            )
            assertEquals(
                "$path's $from must construct UiState.Content exactly once (its publish)",
                1,
                occurrences(body, "UiState.Content("),
            )
            assertEquals(
                "$path's $from must hold no alternate StateFlow write shape",
                0,
                occurrences(body, ".update {") + occurrences(body, "compareAndSet"),
            )
        }
        val detail = mainSource(
            "java/dev/cyberdogs/offleash/ui/detail/DetailScreens.kt",
        )
        val playlistLoad = detail.substringAfter("class PlaylistViewModel")
            .substringBefore("fun loadMore")
        assertEquals(
            "PlaylistViewModel's load must assign its state exactly twice",
            2,
            occurrences(playlistLoad, "_state.value ="),
        )
        assertEquals(
            "PlaylistViewModel's load must construct UiState.Content exactly once",
            1,
            occurrences(playlistLoad, "UiState.Content("),
        )
        val playlistMore = detail.substringAfter("class PlaylistViewModel")
            .substringAfter("fun loadMore()")
            .substringBefore("@Composable")
        assertEquals(
            "playlist loadMore must assign its state exactly once (the merge publish)",
            1,
            occurrences(playlistMore, "_state.value ="),
        )
        assertEquals(
            "playlist loadMore must construct UiState.Content exactly once",
            1,
            occurrences(playlistMore, "UiState.Content("),
        )
        assertEquals(
            "playlist loadMore must compute its fresh tracks as the chunk's " +
                "tracks that are not already on the page, in that order — " +
                "swapping the arguments appends the first page again",
            1,
            occurrences(
                playlistMore,
                "val fresh = dev.cyberdogs.offleash.core.model.newTracksOnly(\n" +
                    "                            latest.tracks,\n" +
                    "                            chunk.tracks,\n" +
                    "                        )",
            ),
        )
        val search = mainSource(
            "java/dev/cyberdogs/offleash/ui/search/SearchScreen.kt",
        )
        val searchBody = search.substringAfter("fun search(query: String")
            .substringBefore("fun loadMore()")
        assertEquals(
            "SearchViewModel.search must assign its state exactly three " +
                "times (Idle on empty, Loading, then the guarded result)",
            3,
            occurrences(searchBody, "_state.value ="),
        )
        assertEquals(
            "SearchViewModel.search must construct SearchUiState.Content exactly once",
            1,
            occurrences(searchBody, "SearchUiState.Content("),
        )
        assertEquals(
            "SearchViewModel.search must hold no alternate StateFlow write shape",
            0,
            occurrences(searchBody, ".update {") + occurrences(searchBody, "compareAndSet"),
        )
        val searchMore = search.substringAfter("fun loadMore()")
            .substringBefore("fun retry()")
        assertEquals(
            "search loadMore must assign its state exactly once (the merge publish)",
            1,
            occurrences(searchMore, "_state.value ="),
        )
        assertEquals(
            "search loadMore must construct SearchUiState.Content exactly once",
            1,
            occurrences(searchMore, "SearchUiState.Content("),
        )
        val library = mainSource(
            "java/dev/cyberdogs/offleash/ui/library/LibraryScreen.kt",
        ).substringAfter("LaunchedEffect(sessionState.signedIn")
            .substringBefore("LazyColumn(Modifier.fillMaxSize())")
        assertEquals(
            "LibraryScreen's load effects must assign ytPlaylists exactly " +
                "twice (Loading, then the try result) — a transformed " +
                "republication after the bound if/else is a third assignment",
            2,
            occurrences(library, "ytPlaylists ="),
        )
        assertEquals(
            "LibraryScreen's load effects must construct UiState.Content exactly once",
            1,
            occurrences(library, "UiState.Content("),
        )
    }

    @Test
    fun offleashRootRouteTableBindsEveryDestinationAndHandoff() {
        val root = mainSource("java/dev/cyberdogs/offleash/ui/OffleashRoot.kt")
        assertEquals(
            "the NavHost must start on the home route",
            1,
            occurrences(root, "startDestination = \"home\""),
        )
        assertEquals(
            "openAlbum must navigate to the album route with the encoded id",
            1,
            occurrences(
                root,
                "    val openAlbum: (String) -> Unit = { id ->\n" +
                    "        navController.navigate(\"album/\${Uri.encode(id)}\")\n" +
                    "    }",
            ),
        )
        assertEquals(
            "openArtist must navigate to the artist route with the encoded id",
            1,
            occurrences(
                root,
                "    val openArtist: (String) -> Unit = { id ->\n" +
                    "        navController.navigate(\"artist/\${Uri.encode(id)}\")\n" +
                    "    }",
            ),
        )
        assertEquals(
            "openPlaylist must navigate to the playlist route, carrying " +
                "params only when the caller offers them",
            1,
            occurrences(
                root,
                "    val openPlaylist: (String, String?) -> Unit = { id, params ->\n" +
                    "        if (params != null) {\n" +
                    "            navController.navigate(\"playlist/\${Uri.encode(id)}?params=\${Uri.encode(params)}\")\n" +
                    "        } else {\n" +
                    "            navController.navigate(\"playlist/\${Uri.encode(id)}\")\n" +
                    "        }\n" +
                    "    }",
            ),
        )
        assertEquals(
            "the route registration must stand verbatim (destination + argument handoffs)",
            1,
            occurrences(root, "                composable(\"home\") {\n" +
                        "                    HomeScreen(\n" +
                        "                        container = container,\n" +
                        "                        onOpenCharts = { navController.navigate(\"charts\") },\n" +
                        "                        onOpenMoods = { navController.navigate(\"moods\") },\n" +
                        "                        onAlbumClick = openAlbum,\n" +
                        "                        onArtistClick = openArtist,\n" +
                        "                        onPlaylistClick = openPlaylist,\n" +
                        "                    )\n" +
                        "                }\n"),
        )
        assertEquals(
            "the route registration must stand verbatim (destination + argument handoffs)",
            1,
            occurrences(root, "                composable(\"charts\") {\n" +
                        "                    ChartsScreen(\n" +
                        "                        container = container,\n" +
                        "                        onAlbumClick = openAlbum,\n" +
                        "                        onArtistClick = openArtist,\n" +
                        "                        onPlaylistClick = openPlaylist,\n" +
                        "                    )\n" +
                        "                }\n"),
        )
        assertEquals(
            "the route registration must stand verbatim (destination + argument handoffs)",
            1,
            occurrences(root, "                composable(\"moods\") {\n" +
                        "                    MoodsScreen(\n" +
                        "                        container = container,\n" +
                        "                        onCategoryClick = { category ->\n" +
                        "                            navController.navigate(\n" +
                        "                                \"mood/\${Uri.encode(category.params)}/\${Uri.encode(category.title)}\",\n" +
                        "                            )\n" +
                        "                        },\n" +
                        "                    )\n" +
                        "                }\n"),
        )
        assertEquals(
            "the route registration must stand verbatim (destination + argument handoffs)",
            1,
            occurrences(root, "                composable(\n" +
                        "                    route = \"mood/{params}/{title}\",\n" +
                        "                    arguments = listOf(\n" +
                        "                        navArgument(\"params\") { type = NavType.StringType },\n" +
                        "                        navArgument(\"title\") { type = NavType.StringType },\n" +
                        "                    ),\n" +
                        "                ) { entry ->\n" +
                        "                    MoodPlaylistsScreen(\n" +
                        "                        container = container,\n" +
                        "                        params = entry.arguments?.getString(\"params\") ?: \"\",\n" +
                        "                        title = entry.arguments?.getString(\"title\") ?: \"\",\n" +
                        "                        onAlbumClick = openAlbum,\n" +
                        "                        onArtistClick = openArtist,\n" +
                        "                        onPlaylistClick = openPlaylist,\n" +
                        "                    )\n" +
                        "                }\n"),
        )
        assertEquals(
            "the route registration must stand verbatim (destination + argument handoffs)",
            1,
            occurrences(root, "                composable(\"search\") {\n" +
                        "                    SearchScreen(\n" +
                        "                        container = container,\n" +
                        "                        onAlbumClick = openAlbum,\n" +
                        "                        onArtistClick = openArtist,\n" +
                        "                        onPlaylistClick = openPlaylist,\n" +
                        "                    )\n" +
                        "                }\n"),
        )
        assertEquals(
            "the route registration must stand verbatim (destination + argument handoffs)",
            1,
            occurrences(root, "                composable(\"library\") {\n" +
                        "                    LibraryScreen(\n" +
                        "                        container = container,\n" +
                        "                        onPlaylistClick = { id ->\n" +
                        "                            navController.navigate(\"localPlaylist/\${Uri.encode(id)}\")\n" +
                        "                        },\n" +
                        "                        onSignInClick = { navController.navigate(\"signin\") },\n" +
                        "                        onRemotePlaylistClick = openPlaylist,\n" +
                        "                        onAlbumClick = openAlbum,\n" +
                        "                        onArtistClick = openArtist,\n" +
                        "                    )\n" +
                        "                }\n"),
        )
        assertEquals(
            "the route registration must stand verbatim (destination + argument handoffs)",
            1,
            occurrences(root, "                composable(\"about\") {\n" +
                        "                    AboutScreen(\n" +
                        "                        container = container,\n" +
                        "                        onOpenDiagnostics = { navController.navigate(\"diagnostics\") },\n" +
                        "                        onSignInClick = { navController.navigate(\"signin\") },\n" +
                        "                        onOpenSettings = { navController.navigate(\"settings\") },\n" +
                        "                    )\n" +
                        "                }\n"),
        )
        assertEquals(
            "the route registration must stand verbatim (destination + argument handoffs)",
            1,
            occurrences(root, "                composable(\"settings\") {\n" +
                        "                    SettingsScreen(\n" +
                        "                        container = container,\n" +
                        "                        onSignInClick = { navController.navigate(\"signin\") },\n" +
                        "                    )\n" +
                        "                }\n"),
        )
        assertEquals(
            "the route registration must stand verbatim (destination + argument handoffs)",
            1,
            occurrences(root, "                composable(\"signin\") {\n" +
                        "                    SignInScreen(\n" +
                        "                        container = container,\n" +
                        "                        onSignedIn = { navController.popBackStack() },\n" +
                        "                        onBack = { navController.popBackStack() },\n" +
                        "                    )\n" +
                        "                }\n"),
        )
        assertEquals(
            "the route registration must stand verbatim (destination + argument handoffs)",
            1,
            occurrences(root, "                composable(\"diagnostics\") {\n" +
                        "                    PlaybackDiagnosticsScreen(container = container)\n" +
                        "                }\n"),
        )
        assertEquals(
            "the route registration must stand verbatim (destination + argument handoffs)",
            1,
            occurrences(root, "                composable(\n" +
                        "                    route = \"album/{id}\",\n" +
                        "                    arguments = listOf(navArgument(\"id\") { type = NavType.StringType }),\n" +
                        "                ) { entry ->\n" +
                        "                    AlbumScreen(\n" +
                        "                        container = container,\n" +
                        "                        browseId = entry.arguments?.getString(\"id\") ?: \"\",\n" +
                        "                        onAlbumClick = openAlbum,\n" +
                        "                        onArtistClick = openArtist,\n" +
                        "                    )\n" +
                        "                }\n"),
        )
        assertEquals(
            "the route registration must stand verbatim (destination + argument handoffs)",
            1,
            occurrences(root, "                composable(\n" +
                        "                    route = \"artist/{id}\",\n" +
                        "                    arguments = listOf(navArgument(\"id\") { type = NavType.StringType }),\n" +
                        "                ) { entry ->\n" +
                        "                    ArtistScreen(\n" +
                        "                        container = container,\n" +
                        "                        browseId = entry.arguments?.getString(\"id\") ?: \"\",\n" +
                        "                        onAlbumClick = openAlbum,\n" +
                        "                        onArtistClick = openArtist,\n" +
                        "                        onPlaylistClick = openPlaylist,\n" +
                        "                    )\n" +
                        "                }\n"),
        )
        assertEquals(
            "the route registration must stand verbatim (destination + argument handoffs)",
            1,
            occurrences(root, "                composable(\n" +
                        "                    route = \"playlist/{id}?params={params}\",\n" +
                        "                    arguments = listOf(\n" +
                        "                        navArgument(\"id\") { type = NavType.StringType },\n" +
                        "                        navArgument(\"params\") {\n" +
                        "                            type = NavType.StringType\n" +
                        "                            nullable = true\n" +
                        "                            defaultValue = null\n" +
                        "                        },\n" +
                        "                    ),\n" +
                        "                ) { entry ->\n" +
                        "                    PlaylistScreen(\n" +
                        "                        container = container,\n" +
                        "                        browseId = entry.arguments?.getString(\"id\") ?: \"\",\n" +
                        "                        params = entry.arguments?.getString(\"params\"),\n" +
                        "                        onAlbumClick = openAlbum,\n" +
                        "                        onArtistClick = openArtist,\n" +
                        "                    )\n" +
                        "                }\n"),
        )
        assertEquals(
            "the route registration must stand verbatim (destination + argument handoffs)",
            1,
            occurrences(root, "                composable(\n" +
                        "                    route = \"localPlaylist/{id}\",\n" +
                        "                    arguments = listOf(navArgument(\"id\") { type = NavType.StringType }),\n" +
                        "                ) { entry ->\n" +
                        "                    LocalPlaylistScreen(\n" +
                        "                        container = container,\n" +
                        "                        playlistId = entry.arguments?.getString(\"id\") ?: \"\",\n" +
                        "                        onAlbumClick = openAlbum,\n" +
                        "                        onArtistClick = openArtist,\n" +
                        "                    )\n" +
                        "                }\n"),
        )
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
