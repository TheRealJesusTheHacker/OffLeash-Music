package dev.cyberdogs.offleash.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.cyberdogs.offleash.AppContainer
import kotlinx.coroutines.delay
import dev.cyberdogs.offleash.playback.showLoadingIndicator
import dev.cyberdogs.offleash.playback.SplashGate
import dev.cyberdogs.offleash.ui.about.AboutScreen
import dev.cyberdogs.offleash.ui.about.PlaybackDiagnosticsScreen
import dev.cyberdogs.offleash.ui.account.SignInScreen
import dev.cyberdogs.offleash.ui.components.ArtworkImage
import dev.cyberdogs.offleash.ui.detail.AlbumScreen
import dev.cyberdogs.offleash.ui.detail.ArtistScreen
import dev.cyberdogs.offleash.ui.detail.PlaylistScreen
import dev.cyberdogs.offleash.ui.home.ChartsScreen
import dev.cyberdogs.offleash.ui.home.HomeScreen
import dev.cyberdogs.offleash.ui.home.MoodPlaylistsScreen
import dev.cyberdogs.offleash.ui.home.MoodsScreen
import dev.cyberdogs.offleash.ui.library.LibraryScreen
import dev.cyberdogs.offleash.ui.library.LocalPlaylistScreen
import dev.cyberdogs.offleash.ui.player.PlayerScreen
import dev.cyberdogs.offleash.ui.search.SearchScreen
import dev.cyberdogs.offleash.ui.settings.SettingsScreen

private val TOP_LEVEL_ROUTES = setOf("home", "search", "library", "about")

@Composable
fun OffleashRoot(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val queueState by container.queueController.queueState.collectAsState()
    val playbackState by container.queueController.playbackState.collectAsState()
    val namedError by container.queueController.namedError.collectAsState()

    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // The expanded player is an overlay, not a nav destination, so
    // without this the system back press fell through to the nav
    // stack: it popped the route UNDER the player (or, on a
    // top-level route, left the app) while the player stayed open.
    // Back closes the player first, like the player's own close
    // button; only then does back mean navigation.
    BackHandler(enabled = playerExpanded) {
        playerExpanded = false
    }

    // Cold-launch splash (2026-10-27, owner's spec): 3.8 seconds,
    // tap anywhere skips, and it never returns once this process has
    // shown it or played anything — the gate's process-lifetime
    // flags decide, this state only drives the exit.
    var splashDismissed by remember { mutableStateOf(false) }
    val showSplash = !splashDismissed && SplashGate.shouldShow(
        playerHasMedia = container.player.currentMediaItem != null,
    )
    LaunchedEffect(showSplash) {
        if (showSplash) {
            SplashGate.markShown()
            delay(SplashGate.SPLASH_DURATION_MS)
            splashDismissed = true
        }
    }

    // Lane G: track-menu items that live on the player (sleep timer,
    // playback speed & pitch) deep-link here — a menu tap anywhere in
    // the app expands the full player.
    val showPlayerRequests by container.showPlayerRequests.collectAsState()
    LaunchedEffect(showPlayerRequests) {
        if (showPlayerRequests > 0) {
            playerExpanded = true
        }
    }

    // Lane G: a failed account-like write surfaces the same way a
    // playback error does — a snackbar from the root, never silent.
    val likeError by container.likeController.error.collectAsState()
    LaunchedEffect(likeError) {
        val message = likeError
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            container.likeController.clearError()
        }
    }

    // Surface playback errors as a snackbar when the full player is closed;
    // the player itself shows the same message in its error banner.
    // Long duration: the named messages run two to three sentences
    // (what happened + what to do), and the default Short snackbar
    // dismissed them before they could be read.
    LaunchedEffect(namedError, playerExpanded) {
        val message = namedError
        if (message != null && !playerExpanded) {
            // The action is the error card's Try again (bug sweep
            // round 9): with the full player closed this snackbar
            // was the ONLY surface for the error, and until now it
            // carried no action at all — the listener had to open
            // the player to retry a track the app already knew how
            // to re-resolve. The like-error snackbar above stays
            // actionless: a failed like has no such retry.
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Try again",
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                container.queueController.retryCurrent()
            }
        }
    }

    val openAlbum: (String) -> Unit = { id ->
        navController.navigate("album/${Uri.encode(id)}")
    }
    val openArtist: (String) -> Unit = { id ->
        navController.navigate("artist/${Uri.encode(id)}")
    }
    val openPlaylist: (String, String?) -> Unit = { id, params ->
        if (params != null) {
            navController.navigate("playlist/${Uri.encode(id)}?params=${Uri.encode(params)}")
        } else {
            navController.navigate("playlist/${Uri.encode(id)}")
        }
    }

    // Immersive surfaces (2026-10-27 bars pass, owner directive):
    // while the splash or the expanded player is up, the system bars
    // hide and return on a swipe from the screen edge, fading again
    // on their own (transient behavior). Everywhere else the bars
    // stay and the layout sizes around them via insets.
    TransientSystemBars(active = showSplash || playerExpanded)

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                val showTabs = currentRoute in TOP_LEVEL_ROUTES
                Column(
                    // When the tabs bar is hidden (every non-top-level
                    // route — sign-in included), the mini-player is
                    // the bottom-most element on the screen and must
                    // carry the navigation-bar inset itself; the
                    // Material NavigationBar applies its own inset
                    // when shown. Without this the mini-player sat in
                    // the system navigation zone on Android 15's
                    // enforced edge-to-edge (2026-10-27 bars pass).
                    modifier = if (showTabs) {
                        Modifier
                    } else {
                        Modifier.navigationBarsPadding()
                    },
                ) {
                    if (queueState.currentTrack != null && !playerExpanded) {
                        MiniPlayer(
                            container = container,
                            onExpand = { playerExpanded = true },
                        )
                    }
                    if (showTabs) {
                        NavigationBar {
                            NavigationBarItem(
                                selected = currentRoute == "home",
                                onClick = {
                                    navController.navigate("home") {
                                        popUpTo("home") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                },
                                icon = {
                                    Icon(imageVector = Icons.Default.Home, contentDescription = null)
                                },
                                label = { Text("Home") },
                            )
                            NavigationBarItem(
                                selected = currentRoute == "search",
                                onClick = {
                                    navController.navigate("search") {
                                        popUpTo("home") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                },
                                icon = {
                                    Icon(imageVector = Icons.Default.Search, contentDescription = null)
                                },
                                label = { Text("Search") },
                            )
                            NavigationBarItem(
                                selected = currentRoute == "library",
                                onClick = {
                                    navController.navigate("library") {
                                        popUpTo("home") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.LibraryMusic,
                                        contentDescription = null,
                                    )
                                },
                                label = { Text("Library") },
                            )
                            NavigationBarItem(
                                selected = currentRoute == "about",
                                onClick = {
                                    navController.navigate("about") {
                                        popUpTo("home") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                },
                                icon = {
                                    Icon(imageVector = Icons.Default.Info, contentDescription = null)
                                },
                                label = { Text("About") },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "home",
                // One motion language for every route change
                // (2026-10-27 polish pass): a short fade with a
                // slight forward slide in, a plain fade back out.
                enterTransition = {
                    fadeIn(tween(NAV_TRANSITION_MS)) +
                        slideInHorizontally(tween(NAV_TRANSITION_MS)) { it / 8 }
                },
                exitTransition = { fadeOut(tween(NAV_TRANSITION_MS)) },
                popEnterTransition = { fadeIn(tween(NAV_TRANSITION_MS)) },
                popExitTransition = {
                    fadeOut(tween(NAV_TRANSITION_MS)) +
                        slideOutHorizontally(tween(NAV_TRANSITION_MS)) { it / 8 }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                composable("home") {
                    HomeScreen(
                        container = container,
                        onOpenCharts = { navController.navigate("charts") },
                        onOpenMoods = { navController.navigate("moods") },
                        onAlbumClick = openAlbum,
                        onArtistClick = openArtist,
                        onPlaylistClick = openPlaylist,
                    )
                }
                composable("charts") {
                    ChartsScreen(
                        container = container,
                        onAlbumClick = openAlbum,
                        onArtistClick = openArtist,
                        onPlaylistClick = openPlaylist,
                    )
                }
                composable("moods") {
                    MoodsScreen(
                        container = container,
                        onCategoryClick = { category ->
                            navController.navigate(
                                "mood/${Uri.encode(category.params)}/${Uri.encode(category.title)}",
                            )
                        },
                    )
                }
                composable(
                    route = "mood/{params}/{title}",
                    arguments = listOf(
                        navArgument("params") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType },
                    ),
                ) { entry ->
                    MoodPlaylistsScreen(
                        container = container,
                        params = entry.arguments?.getString("params") ?: "",
                        title = entry.arguments?.getString("title") ?: "",
                        onAlbumClick = openAlbum,
                        onArtistClick = openArtist,
                        onPlaylistClick = openPlaylist,
                    )
                }
                composable("search") {
                    SearchScreen(
                        container = container,
                        onAlbumClick = openAlbum,
                        onArtistClick = openArtist,
                        onPlaylistClick = openPlaylist,
                    )
                }
                composable("library") {
                    LibraryScreen(
                        container = container,
                        onPlaylistClick = { id ->
                            navController.navigate("localPlaylist/${Uri.encode(id)}")
                        },
                        onSignInClick = { navController.navigate("signin") },
                        onRemotePlaylistClick = openPlaylist,
                        onAlbumClick = openAlbum,
                        onArtistClick = openArtist,
                    )
                }
                composable("about") {
                    AboutScreen(
                        container = container,
                        onOpenDiagnostics = { navController.navigate("diagnostics") },
                        onSignInClick = { navController.navigate("signin") },
                        onOpenSettings = { navController.navigate("settings") },
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        container = container,
                        onSignInClick = { navController.navigate("signin") },
                    )
                }
                composable("signin") {
                    SignInScreen(
                        container = container,
                        onSignedIn = { navController.popBackStack() },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("diagnostics") {
                    PlaybackDiagnosticsScreen(container = container)
                }
                composable(
                    route = "album/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    AlbumScreen(
                        container = container,
                        browseId = entry.arguments?.getString("id") ?: "",
                        onAlbumClick = openAlbum,
                        onArtistClick = openArtist,
                    )
                }
                composable(
                    route = "artist/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    ArtistScreen(
                        container = container,
                        browseId = entry.arguments?.getString("id") ?: "",
                        onAlbumClick = openAlbum,
                        onArtistClick = openArtist,
                        onPlaylistClick = openPlaylist,
                    )
                }
                composable(
                    route = "playlist/{id}?params={params}",
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType },
                        navArgument("params") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) { entry ->
                    PlaylistScreen(
                        container = container,
                        browseId = entry.arguments?.getString("id") ?: "",
                        params = entry.arguments?.getString("params"),
                        onAlbumClick = openAlbum,
                        onArtistClick = openArtist,
                    )
                }
                composable(
                    route = "localPlaylist/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    LocalPlaylistScreen(
                        container = container,
                        playlistId = entry.arguments?.getString("id") ?: "",
                        onAlbumClick = openAlbum,
                        onArtistClick = openArtist,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showSplash,
            exit = fadeOut(tween(SPLASH_EXIT_MS)),
        ) {
            SplashScreen(onSkip = { splashDismissed = true })
        }

        if (playerExpanded) {
            PlayerScreen(
                container = container,
                onClose = { playerExpanded = false },
                onAlbumClick = { id ->
                    playerExpanded = false
                    openAlbum(id)
                },
                onArtistClick = { id ->
                    playerExpanded = false
                    openArtist(id)
                },
            )
        }
    }
}

@Composable
private fun MiniPlayer(
    container: AppContainer,
    onExpand: () -> Unit,
) {
    val queueState by container.queueController.queueState.collectAsState()
    val playbackState by container.queueController.playbackState.collectAsState()
    val track = queueState.currentTrack ?: return

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            // TalkBack gets the tap's purpose, not just the track
            // text it merges in (chaos sweep round 2).
            .clickable(onClickLabel = "Open the full player", onClick = onExpand),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkImage(
                url = track.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artists.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showLoadingIndicator(playbackState.isResolving, playbackState.isBuffering)) {
                // A resolve (any road, any ladder step) or buffering is
                // in flight: show progress in place of the play/pause
                // button so a tap never looks like a silent stall.
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(48.dp)
                        .padding(12.dp),
                )
            } else {
                IconButton(onClick = { container.queueController.togglePlayPause() }) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) {
                            Icons.Default.Pause
                        } else {
                            Icons.Default.PlayArrow
                        },
                        contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                    )
                }
            }
            IconButton(onClick = { container.queueController.next() }) {
                Icon(imageVector = Icons.Default.SkipNext, contentDescription = "Next track")
            }
        }
    }
}

/**
 * Transient system bars for the immersive surfaces (2026-10-27
 * bars pass, owner directive: "make them disappear unless I swipe
 * up"). While [active], the status and navigation bars hide; a
 * swipe from a screen edge reveals them and they fade again on
 * their own ([WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE]).
 * The reveal is the system's own gesture — it never consumes the
 * app's taps, so the splash's tap-to-skip and the player's controls
 * are undisturbed, and the splash's 3.8-second timer (a root
 * effect, independent of the bars) is untouched. When [active]
 * turns false — and again if this leaves composition — the bars
 * are restored, so the app can never strand the user without them.
 *
 * The behavior is identical under 3-button and gesture navigation:
 * the controller hides/shows the same system-bar types either way,
 * and the revealed bar's height is the system's, which the inset
 * handling elsewhere already sizes around per mode.
 */
@Composable
private fun TransientSystemBars(active: Boolean) {
    val context = LocalContext.current
    DisposableEffect(context, active) {
        val window = context.findActivity()?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (active) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (!active && window != null) {
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

/** The hosting activity behind a (possibly wrapped) context. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** Route-change motion duration (polish pass, 2026-10-27). */
private const val NAV_TRANSITION_MS = 220

/** Splash exit fade duration (the splash itself is [SplashGate]'s spec). */
private const val SPLASH_EXIT_MS = 250
