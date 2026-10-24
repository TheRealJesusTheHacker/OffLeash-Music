package dev.cyberdogs.offleash.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.cyberdogs.offleash.AppContainer
import dev.cyberdogs.offleash.playback.showLoadingIndicator
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

    // Surface playback errors as a snackbar when the full player is closed;
    // the player itself shows the same message in its error banner.
    LaunchedEffect(namedError, playerExpanded) {
        val message = namedError
        if (message != null && !playerExpanded) {
            snackbarHostState.showSnackbar(message)
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

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                Column {
                    if (queueState.currentTrack != null && !playerExpanded) {
                        MiniPlayer(
                            container = container,
                            onExpand = { playerExpanded = true },
                        )
                    }
                    if (currentRoute in TOP_LEVEL_ROUTES) {
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
                    )
                }
                composable(
                    route = "localPlaylist/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    LocalPlaylistScreen(
                        container = container,
                        playlistId = entry.arguments?.getString("id") ?: "",
                    )
                }
            }
        }

        if (playerExpanded) {
            PlayerScreen(
                container = container,
                onClose = { playerExpanded = false },
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
            .clickable(onClick = onExpand),
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
