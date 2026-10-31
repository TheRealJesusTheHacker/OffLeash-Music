package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 49 sweep contracts (confirmation round — breaking round
 * 48's fixes):
 *
 * (1) The publish census leaves the slices. R48 made the publish
 * binding semantic — but only INSIDE its named slices, and the
 * slices are not the state holders. Proven live this round, each
 * green across the full app suite (602):
 *  - a private `publishNarrowed()` member added to
 *    SearchViewModel AFTER `fun retry()` (outside both the R47
 *    and R48 slices, which end at `fun retry()`), called by one
 *    needle-free line from loadMore, re-published the search
 *    narrowed to its first section after every merge;
 *  - a second `LaunchedEffect` in LibraryScreen placed AFTER
 *    the counted window (which ends at the LazyColumn needle)
 *    re-published `ytPlaylists` narrowed to one playlist;
 *  - an extension in a NEW file (`SearchDoctor.kt`) republishing
 *    through the public state flow cast back to MutableStateFlow
 *    — no `_state.value =` text anywhere, and no pin censused
 *    the cast spelling;
 *  - a helper in a NEW home-package file clearing the standing
 *    feed's sections IN PLACE through a MutableList cast (the
 *    parser returns its own MutableList instances as the feed's
 *    lists — the round-39 road), called from `onChipClick`,
 *    whose body R30 only contains-checks; R30's cast ban
 *    enumerates four home files, and a fifth escapes it.
 * The fix is a MODULE census, not wider slices: every
 * `_state.value =` / `_uiState.value =` assignment site in
 * production app code is enumerated per file with its shipped
 * count (a helper in ANY existing file moves a count; a helper
 * in a NEW file breaks the file-set census), Library's
 * `ytPlaylists =` sites are counted file-wide, the cast-back
 * spellings are banned module-wide, and `compareAndSet` (the
 * remaining alternate StateFlow write shape) is asserted at
 * zero module-wide.
 *
 * (2) The track-menu navigation joints. R44/R47 bound the
 * SectionRow branches, the rememberFeedActions factory and its
 * hosts' NAMED delegations — but `rememberTrackActions` takes
 * its album/artist lambdas POSITIONALLY, and no pin read any
 * of its ten call sites or TrackMenu's own wiring. Proven live
 * this round (602): swapping the two lambdas at AlbumScreen's
 * call cross-wired every track menu on the album page (the
 * "Artist" item opening the album route and vice versa), and
 * swapping TrackMenu's two menu-item invocations did the same
 * for every track menu in the app. Every call site is bound
 * below in its shipped form, the crossed forms are banned
 * module-wide, TrackMenu's factory delegations, browse-id
 * derivations and menu invocations are bound verbatim, and
 * the screens' direct navigation invocations (the Home liked
 * card, the Library rows, the Moods category grid) are bound
 * with their argument expressions.
 *
 * The ViewModel/UI files are Compose-bound, so the shape is
 * the pin (house style; CI is the compile gate).
 */
class R49SweepContractTest {

    @Test
    fun stateWritesAreCensusedModuleWide() {
        val files = moduleSources()
        assertEquals(
            "the app module's Kotlin file set must be exactly the " +
                "shipped set — a new production file is the one " +
                "vehicle every slice pin cannot see (a helper, an " +
                "extension, a cast-back writer), so adding one is " +
                "a census event, never a silent landing",
            SHIPPED_FILES,
            files.keys.sorted(),
        )
        val expectedStateWrites = mapOf(
            "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt" to (7 to 0),
            "dev/cyberdogs/offleash/ui/home/ChartsScreen.kt" to (2 to 0),
            "dev/cyberdogs/offleash/ui/home/HomeScreen.kt" to (2 to 0),
            "dev/cyberdogs/offleash/ui/home/MoodsScreen.kt" to (4 to 0),
            "dev/cyberdogs/offleash/ui/search/SearchScreen.kt" to (4 to 0),
            "dev/cyberdogs/offleash/data/SessionManager.kt" to (0 to 1),
            "dev/cyberdogs/offleash/data/GoogleSessionManager.kt" to (0 to 9),
        )
        for ((path, text) in files) {
            val expected = expectedStateWrites[path] ?: (0 to 0)
            assertEquals(
                "$path must hold exactly ${expected.first} `_state.value =` " +
                    "assignment(s) — every state write in the module is " +
                    "enumerated; a republication helper in any region " +
                    "of any file (a ViewModel's tail beyond the R48 " +
                    "slices included) is one more",
                expected.first,
                occurrences(text, "_state.value ="),
            )
            assertEquals(
                "$path must hold exactly ${expected.second} `_uiState.value =` assignment(s)",
                expected.second,
                occurrences(text, "_uiState.value ="),
            )
            assertEquals(
                "$path must hold no alternate StateFlow write shape " +
                    "(compareAndSet is the one write that needs no " +
                    "`.value =` text)",
                0,
                occurrences(text, "compareAndSet"),
            )
            for (cast in listOf(
                "as MutableStateFlow", "as? MutableStateFlow",
                "as MutableList", "as? MutableList",
                "as ArrayList", "as? ArrayList",
            )) {
                assertEquals(
                    "$path must hold no `$cast` — casting a published " +
                        "flow back to its mutable type, or a published " +
                        "list to MutableList, writes state with no " +
                        "assignment and no construction for any count " +
                        "to see (the round-39 in-place road, and its " +
                        "cast-back twin, both run through this spelling)",
                    0,
                    occurrences(text, cast),
                )
            }
        }
        val library = files.getValue(
            "dev/cyberdogs/offleash/ui/library/LibraryScreen.kt",
        )
        assertEquals(
            "LibraryScreen must assign ytPlaylists exactly twice in " +
                "the WHOLE file (Loading, then the try result) — the " +
                "R48 window ends at the LazyColumn needle, and a " +
                "republication effect placed after it was invisible " +
                "to that window while the account library rendered " +
                "narrowed to one playlist",
            2,
            occurrences(library, "ytPlaylists ="),
        )
        assertEquals(
            "LibraryScreen must construct UiState.Content exactly once in the whole file",
            1,
            occurrences(library, "UiState.Content("),
        )
    }

    @Test
    fun trackNavigationJointsAndInvocationsAreBoundAtEverySite() {
        val files = moduleSources()
        for ((path, text) in files) {
            for (crossed in listOf(
                "onAlbumClick = onArtistClick",
                "onArtistClick = onAlbumClick",
                "rememberTrackActions(container, onArtistClick, onAlbumClick)",
                "container, onArtistClick, onAlbumClick,",
                "actions.onAlbumClick(artistId)",
                "actions.onArtistClick(albumId)",
            )) {
                assertEquals(
                    "$path must never cross its track navigation " +
                        "lambdas (`$crossed`) — album and artist " +
                        "destinations take the same (String) type, " +
                        "so a crossed joint compiles and only a " +
                        "census like this one can see it",
                    0,
                    occurrences(text, crossed),
                )
            }
        }
        var singleLineCalls = 0
        for (text in files.values) {
            singleLineCalls += occurrences(
                text,
                "rememberTrackActions(container, onAlbumClick, onArtistClick)",
            )
        }
        assertEquals(
            "the module must hold exactly seven single-line " +
                "rememberTrackActions calls in shipped argument order " +
                "(Album, Artist and Playlist in DetailScreens; Charts; " +
                "Home; MoodPlaylists; Search) — the factory takes the " +
                "lambdas positionally, so the call text is the only " +
                "binding the joint can have",
            7,
            singleLineCalls,
        )
        val library = files.getValue(
            "dev/cyberdogs/offleash/ui/library/LibraryScreen.kt",
        )
        assertEquals(
            "Library's two fully-qualified rememberTrackActions calls " +
                "must pass the lambdas in shipped order",
            2,
            occurrences(library, "container, onAlbumClick, onArtistClick,"),
        )
        val player = files.getValue(
            "dev/cyberdogs/offleash/ui/player/PlayerScreen.kt",
        )
        assertEquals(
            "PlayerScreen must hand its lambdas by name exactly twice " +
                "(the QueueScreen handoff and the queue's own factory call)",
            2,
            occurrences(player, "onAlbumClick = onAlbumClick"),
        )
        assertEquals(
            "PlayerScreen must hand its lambdas by name exactly twice",
            2,
            occurrences(player, "onArtistClick = onArtistClick"),
        )
        val menu = files.getValue(
            "dev/cyberdogs/offleash/ui/components/TrackMenu.kt",
        )
        assertEquals(
            "rememberTrackActions must build TrackActions with the " +
                "shipped named delegations",
            1,
            occurrences(
                menu,
                "        onArtistClick = onArtistClick,\n" +
                    "        onAlbumClick = onAlbumClick,",
            ),
        )
        assertEquals(
            "the menu's Artist item must derive its id from the " +
                "track's artist browse id",
            1,
            occurrences(
                menu,
                "track.artistBrowseId?.takeIf { it.isNotBlank() }?.let { artistId ->",
            ),
        )
        assertEquals(
            "the menu's Album item must derive its id from the " +
                "track's album browse id",
            1,
            occurrences(
                menu,
                "track.albumBrowseId?.takeIf { it.isNotBlank() }?.let { albumId ->",
            ),
        )
        assertEquals(
            "the Artist menu item must invoke the artist lambda with the artist id",
            1,
            occurrences(menu, "actions.onArtistClick(artistId)"),
        )
        assertEquals(
            "the Album menu item must invoke the album lambda with the album id",
            1,
            occurrences(menu, "actions.onAlbumClick(albumId)"),
        )
        val home = files.getValue(
            "dev/cyberdogs/offleash/ui/home/HomeScreen.kt",
        )
        assertEquals(
            "the Home liked-music card must open the Liked Songs " +
                "playlist through the playlist lambda",
            1,
            occurrences(home, "onPlaylistClick(LIKED_MUSIC_BROWSE_ID, null)"),
        )
        assertEquals(
            "a Library account-playlist row must open the remote " +
                "playlist route with the row's own browse id (not " +
                "its title, and not the local playlist id space)",
            1,
            occurrences(library, "onRemotePlaylistClick(playlist.browseId, null)"),
        )
        assertEquals(
            "the Library liked-songs row must open the Liked Songs playlist",
            1,
            occurrences(library, "onRemotePlaylistClick(\"VLLM\", null)"),
        )
        assertEquals(
            "a Library local-playlist row must open the local route " +
                "with the playlist's local id",
            1,
            occurrences(library, ".clickable { onPlaylistClick(playlist.id) }"),
        )
        val moods = files.getValue(
            "dev/cyberdogs/offleash/ui/home/MoodsScreen.kt",
        )
        assertEquals(
            "a mood grid card must report the whole category (its " +
                "params and title travel together into the route)",
            1,
            occurrences(moods, "onClick = { onCategoryClick(category) }"),
        )
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    /**
     * Every production Kotlin file of the app module, keyed by
     * its path relative to the module's java root.
     */
    private fun moduleSources(): Map<String, String> {
        var dir: File? = File(".").absoluteFile
        var hops = 0
        var root: File? = null
        while (dir != null && hops < 8 && root == null) {
            for (candidate in listOf(
                File(dir, "src/main/java"),
                File(dir, "app/src/main/java"),
            )) {
                if (File(candidate, "dev/cyberdogs/offleash/ui/OffleashRoot.kt").isFile) {
                    root = candidate
                    break
                }
            }
            dir = dir.parentFile
            hops++
        }
        val javaRoot = root ?: throw AssertionError(
            "app/src/main/java not found from " + File(".").absolutePath,
        )
        val out = sortedMapOf<String, String>()
        javaRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .forEach { file ->
                val relative = file.relativeTo(javaRoot).invariantSeparatorsPath
                out[relative] = file.readText()
            }
        assertTrue("module walk must find the production sources", out.isNotEmpty())
        return out
    }

    private companion object {
        val SHIPPED_FILES: List<String> = listOf(
            "dev/cyberdogs/offleash/MainActivity.kt",
            "dev/cyberdogs/offleash/OffleashApp.kt",
            "dev/cyberdogs/offleash/account/AutoDownloadLikedWorker.kt",
            "dev/cyberdogs/offleash/account/GoogleSessionKeepAliveWorker.kt",
            "dev/cyberdogs/offleash/data/AppPreferences.kt",
            "dev/cyberdogs/offleash/data/AtomicFileWrite.kt",
            "dev/cyberdogs/offleash/data/BearerRetryTransport.kt",
            "dev/cyberdogs/offleash/data/CrashReporter.kt",
            "dev/cyberdogs/offleash/data/DownloadManager.kt",
            "dev/cyberdogs/offleash/data/DownloadStore.kt",
            "dev/cyberdogs/offleash/data/Downloads.kt",
            "dev/cyberdogs/offleash/data/GooglePendingGrantStore.kt",
            "dev/cyberdogs/offleash/data/GoogleSessionManager.kt",
            "dev/cyberdogs/offleash/data/GoogleSignInSeenMarker.kt",
            "dev/cyberdogs/offleash/data/GoogleTokenStore.kt",
            "dev/cyberdogs/offleash/data/LikeController.kt",
            "dev/cyberdogs/offleash/data/OkHttpTransport.kt",
            "dev/cyberdogs/offleash/data/ParityPreferences.kt",
            "dev/cyberdogs/offleash/data/RangeInterceptor.kt",
            "dev/cyberdogs/offleash/data/SecretBox.kt",
            "dev/cyberdogs/offleash/data/SessionManager.kt",
            "dev/cyberdogs/offleash/data/SessionStore.kt",
            "dev/cyberdogs/offleash/data/SignInDiagnostics.kt",
            "dev/cyberdogs/offleash/data/Stores.kt",
            "dev/cyberdogs/offleash/playback/BatteryOptimization.kt",
            "dev/cyberdogs/offleash/playback/LikeState.kt",
            "dev/cyberdogs/offleash/playback/LyricsSync.kt",
            "dev/cyberdogs/offleash/playback/NotificationPermission.kt",
            "dev/cyberdogs/offleash/playback/PlaybackDiagnostics.kt",
            "dev/cyberdogs/offleash/playback/PlaybackRecovery.kt",
            "dev/cyberdogs/offleash/playback/PlaybackService.kt",
            "dev/cyberdogs/offleash/playback/PlaybackSpeed.kt",
            "dev/cyberdogs/offleash/playback/QueueController.kt",
            "dev/cyberdogs/offleash/playback/RadioQueue.kt",
            "dev/cyberdogs/offleash/playback/SleepTimer.kt",
            "dev/cyberdogs/offleash/playback/SplashGate.kt",
            "dev/cyberdogs/offleash/ui/ErrorMessages.kt",
            "dev/cyberdogs/offleash/ui/OffleashRoot.kt",
            "dev/cyberdogs/offleash/ui/SplashScreen.kt",
            "dev/cyberdogs/offleash/ui/about/AboutScreen.kt",
            "dev/cyberdogs/offleash/ui/about/PlaybackDiagnosticsScreen.kt",
            "dev/cyberdogs/offleash/ui/account/SignInScreen.kt",
            "dev/cyberdogs/offleash/ui/components/BatteryRestrictionCard.kt",
            "dev/cyberdogs/offleash/ui/components/Common.kt",
            "dev/cyberdogs/offleash/ui/components/TrackMenu.kt",
            "dev/cyberdogs/offleash/ui/detail/DetailScreens.kt",
            "dev/cyberdogs/offleash/ui/home/ChartsScreen.kt",
            "dev/cyberdogs/offleash/ui/home/HomeChrome.kt",
            "dev/cyberdogs/offleash/ui/home/HomeScreen.kt",
            "dev/cyberdogs/offleash/ui/home/MoodsScreen.kt",
            "dev/cyberdogs/offleash/ui/library/LibraryScreen.kt",
            "dev/cyberdogs/offleash/ui/player/PlayerScreen.kt",
            "dev/cyberdogs/offleash/ui/search/SearchScreen.kt",
            "dev/cyberdogs/offleash/ui/settings/SettingsScreen.kt",
            "dev/cyberdogs/offleash/ui/theme/Theme.kt",
        )
    }
}
