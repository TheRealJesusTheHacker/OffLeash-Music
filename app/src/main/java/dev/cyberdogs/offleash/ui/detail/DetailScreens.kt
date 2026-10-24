package dev.cyberdogs.offleash.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cyberdogs.offleash.AppContainer
import dev.cyberdogs.offleash.core.api.AuthSession
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.model.AlbumPage
import dev.cyberdogs.offleash.core.model.ArtistPage
import dev.cyberdogs.offleash.core.model.PlaylistPage
import dev.cyberdogs.offleash.core.model.Track
import dev.cyberdogs.offleash.ui.ErrorMessages
import dev.cyberdogs.offleash.ui.components.ArtworkImage
import dev.cyberdogs.offleash.ui.components.ErrorPanel
import dev.cyberdogs.offleash.ui.components.LoadingPanel
import dev.cyberdogs.offleash.ui.components.SectionRow
import dev.cyberdogs.offleash.ui.components.TrackRow
import dev.cyberdogs.offleash.ui.components.UiState
import dev.cyberdogs.offleash.ui.components.rememberFeedActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Shared header + track list
// ---------------------------------------------------------------------------

@Composable
private fun DetailHeader(
    title: String,
    meta: String,
    description: String?,
    thumbnailUrl: String?,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtworkImage(
            url = thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.size(200.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = meta,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPlayAll) { Text("Play all") }
            OutlinedButton(onClick = onShuffle) { Text("Shuffle") }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.trackItems(
    tracks: List<Track>,
    container: AppContainer,
    playlists: List<dev.cyberdogs.offleash.core.model.LocalPlaylist>,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    itemsIndexed(tracks) { index, track ->
        TrackRow(
            track = track,
            onClick = { container.queueController.playTracks(tracks, index) },
            onPlayNext = { container.queueController.playNext(it) },
            onAddToQueue = { container.queueController.addToQueue(it) },
            playlists = playlists,
            onAddToPlaylist = { playlist, picked ->
                scope.launch { container.localPlaylistStore.addTrack(playlist.id, picked) }
            },
        )
    }
}

private fun playAll(container: AppContainer, tracks: List<Track>) {
    if (tracks.isNotEmpty()) container.queueController.playTracks(tracks, 0)
}

private fun shuffleAll(container: AppContainer, tracks: List<Track>) {
    if (tracks.isEmpty()) return
    container.queueController.setShuffle(true)
    container.queueController.playTracks(tracks, 0)
}

// ---------------------------------------------------------------------------
// Album
// ---------------------------------------------------------------------------

class AlbumViewModel(
    private val client: YoutubeiClient,
    private val browseId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<AlbumPage>>(UiState.Loading)
    val state: StateFlow<UiState<AlbumPage>> = _state

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                UiState.Content(client.album(browseId))
            } catch (e: Exception) {
                UiState.Error(ErrorMessages.userMessage(e))
            }
        }
    }
}

@Composable
fun AlbumScreen(container: AppContainer, browseId: String) {
    val viewModel: AlbumViewModel =
        viewModel(key = browseId) { AlbumViewModel(container.client, browseId) }
    val state by viewModel.state.collectAsState()

    when (val current = state) {
        is UiState.Loading -> LoadingPanel()
        is UiState.Error -> ErrorPanel(message = current.message, onRetry = viewModel::load)
        is UiState.Content -> {
            val page = current.data
            val album = page.album
            val meta = listOfNotNull(
                album.artist?.takeIf { it.isNotBlank() },
                album.year?.toString()?.takeIf { it.isNotBlank() },
            ).joinToString(" • ")
            val playlists by container.localPlaylistStore.playlists.collectAsState()
            val scope = rememberCoroutineScope()
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    DetailHeader(
                        title = album.title,
                        meta = meta,
                        description = page.description,
                        thumbnailUrl = album.thumbnailUrl,
                        onPlayAll = { playAll(container, page.tracks) },
                        onShuffle = { shuffleAll(container, page.tracks) },
                    )
                }
                trackItems(
                    tracks = page.tracks,
                    container = container,
                    playlists = playlists,
                    scope = scope,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Artist
// ---------------------------------------------------------------------------

class ArtistViewModel(
    private val client: YoutubeiClient,
    private val browseId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<ArtistPage>>(UiState.Loading)
    val state: StateFlow<UiState<ArtistPage>> = _state

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                UiState.Content(client.artist(browseId))
            } catch (e: Exception) {
                UiState.Error(ErrorMessages.userMessage(e))
            }
        }
    }
}

@Composable
fun ArtistScreen(
    container: AppContainer,
    browseId: String,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String, String?) -> Unit,
) {
    val viewModel: ArtistViewModel =
        viewModel(key = browseId) { ArtistViewModel(container.client, browseId) }
    val state by viewModel.state.collectAsState()
    val actions = rememberFeedActions(
        queueController = container.queueController,
        playlistStore = container.localPlaylistStore,
        onAlbumClick = onAlbumClick,
        onArtistClick = onArtistClick,
        onPlaylistClick = onPlaylistClick,
    )

    when (val current = state) {
        is UiState.Loading -> LoadingPanel()
        is UiState.Error -> ErrorPanel(message = current.message, onRetry = viewModel::load)
        is UiState.Content -> {
            val page = current.data
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ArtworkImage(
                            url = page.thumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier.size(200.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = page.name,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                items(page.sections) { section ->
                    SectionRow(section = section, actions = actions)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Playlist (remote, from YouTube Music)
// ---------------------------------------------------------------------------

/**
 * Remote playlist page — auth-aware in Phase 2. The load carries the
 * current session when signed in (this is also how the Liked Songs
 * playlist, browseId "VLLM", loads) and no session when signed out, so
 * public playlists keep working anonymously. Album and artist pages
 * stay anonymous by design (out of Phase 2 scope).
 */
class PlaylistViewModel(
    private val client: YoutubeiClient,
    private val browseId: String,
    private val params: String?,
    private val authProvider: () -> AuthSession? = { null },
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<PlaylistPage>>(UiState.Loading)
    val state: StateFlow<UiState<PlaylistPage>> = _state

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                UiState.Content(client.playlist(browseId, params, auth = authProvider()))
            } catch (e: Exception) {
                UiState.Error(ErrorMessages.userMessage(e))
            }
        }
    }
}

@Composable
fun PlaylistScreen(container: AppContainer, browseId: String, params: String?) {
    val viewModel: PlaylistViewModel = viewModel(key = browseId + (params ?: "")) {
        PlaylistViewModel(container.client, browseId, params) {
            container.sessionManager.currentAuthSession()
        }
    }
    val state by viewModel.state.collectAsState()
    val sessionState by container.sessionManager.uiState.collectAsState()

    // Reload when sign-in state flips after the initial load, so an
    // account playlist (e.g. Liked Songs) opened signed-out picks up the
    // session once the user signs in — and drops it on sign-out.
    var authEffectSeen by remember { mutableStateOf(false) }
    LaunchedEffect(sessionState.signedIn) {
        if (authEffectSeen) {
            viewModel.load()
        } else {
            authEffectSeen = true
        }
    }

    when (val current = state) {
        is UiState.Loading -> LoadingPanel()
        is UiState.Error -> ErrorPanel(message = current.message, onRetry = viewModel::load)
        is UiState.Content -> {
            val page = current.data
            val meta = listOfNotNull(
                page.author?.takeIf { it.isNotBlank() },
                "${page.tracks.size} tracks",
            ).joinToString(" • ")
            val playlists by container.localPlaylistStore.playlists.collectAsState()
            val scope = rememberCoroutineScope()
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    DetailHeader(
                        title = page.title,
                        meta = meta,
                        description = null,
                        thumbnailUrl = page.thumbnailUrl,
                        onPlayAll = { playAll(container, page.tracks) },
                        onShuffle = { shuffleAll(container, page.tracks) },
                    )
                }
                trackItems(
                    tracks = page.tracks,
                    container = container,
                    playlists = playlists,
                    scope = scope,
                )
            }
        }
    }
}
