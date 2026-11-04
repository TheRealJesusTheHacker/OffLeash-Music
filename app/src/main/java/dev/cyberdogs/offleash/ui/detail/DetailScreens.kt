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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cyberdogs.offleash.AppContainer
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
import dev.cyberdogs.offleash.ui.components.rememberTrackActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
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
    // False when the page returned no tracks: Play all / Shuffle
    // render disabled instead of as live-looking buttons whose tap
    // silently does nothing (LocalPlaylistScreen already disables
    // its twins in this state; round 4).
    actionsEnabled: Boolean = true,
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
            Button(onClick = onPlayAll, enabled = actionsEnabled) { Text("Play all") }
            OutlinedButton(onClick = onShuffle, enabled = actionsEnabled) { Text("Shuffle") }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.trackItems(
    tracks: List<Track>,
    container: AppContainer,
    playlists: List<dev.cyberdogs.offleash.core.model.LocalPlaylist>,
    scope: kotlinx.coroutines.CoroutineScope,
    trackActions: dev.cyberdogs.offleash.ui.components.TrackActions,
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
            trackActions = trackActions,
            onCreatePlaylist = { name, picked ->
                scope.launch {
                    val created = container.localPlaylistStore.createPlaylist(name)
                    container.localPlaylistStore.addTrack(created.id, picked)
                }
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

    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        // Stale-load guard (bug sweep round 1): cancel the previous
        // load; publish only while this job is still active.
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                UiState.Content(client.album(browseId))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                UiState.Error(ErrorMessages.userMessage(e))
            }
            if (isActive) {
                _state.value = result
            }
        }
    }
}

@Composable
fun AlbumScreen(
    container: AppContainer,
    browseId: String,
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
) {
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
            val trackActions = rememberTrackActions(container, onAlbumClick, onArtistClick)
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    DetailHeader(
                        title = album.title,
                        meta = meta,
                        description = page.description,
                        thumbnailUrl = album.thumbnailUrl,
                        onPlayAll = { playAll(container, page.tracks) },
                        onShuffle = { shuffleAll(container, page.tracks) },
                        actionsEnabled = page.tracks.isNotEmpty(),
                    )
                }
                if (page.tracks.isEmpty()) {
                    item {
                        Text(
                            text = "No tracks were returned for this album.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                trackItems(
                    tracks = page.tracks,
                    container = container,
                    playlists = playlists,
                    scope = scope,
                    trackActions = trackActions,
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

    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        // Stale-load guard (bug sweep round 1): see AlbumViewModel.
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                UiState.Content(client.artist(browseId))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                UiState.Error(ErrorMessages.userMessage(e))
            }
            if (isActive) {
                _state.value = result
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
        trackActions = rememberTrackActions(container, onAlbumClick, onArtistClick),
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
                // The empty state keys on the sections alone
                // (round 21): an artist page whose browse returned
                // a header but no sections must SAY it came back
                // empty, instead of leaving a silent blank under
                // the header. Every sibling browse surface (the
                // album and playlist pages in this file, Charts,
                // Moods, Home, Search) names its empty result.
                if (page.sections.isEmpty()) {
                    item {
                        Text(
                            text = "No content was returned for this artist.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
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
 * Remote playlist page (0.9.0): public playlists load anonymously
 * — YouTube Music is the music source and no account stands
 * between the listener and a public playlist. (The account Liked
 * Songs page this surface used to serve is gone with the sign-in
 * product; OffLeash's own Liked songs live in the Library.)
 */
class PlaylistViewModel(
    private val client: YoutubeiClient,
    private val browseId: String,
    private val params: String?,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<PlaylistPage>>(UiState.Loading)
    val state: StateFlow<UiState<PlaylistPage>> = _state

    private var loadJob: kotlinx.coroutines.Job? = null

    // Continuation paging (bug sweep round 1): the first page's
    // offered token starts the tracker; loadMore() appends the next
    // chunk's NEW tracks only, and the tracker ends paging on an
    // absent or repeated token, so a misbehaving server can't loop
    // the list forever.
    private val continuationTracker =
        dev.cyberdogs.offleash.core.model.ContinuationTracker()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore

    private val _loadMoreError = MutableStateFlow<String?>(null)
    val loadMoreError: StateFlow<String?> = _loadMoreError

    private var loadingMore = false

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        _hasMore.value = false
        _loadMoreError.value = null
        continuationTracker.reset()
        // Stale-load guard (bug sweep round 1): see AlbumViewModel.
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            var fetched: PlaylistPage? = null
            val result = try {
                val page = client.playlist(browseId, params, auth = null)
                fetched = page
                UiState.Content(page)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                UiState.Error(ErrorMessages.userMessage(e))
            }
            // Round 5: the tracker start lives INSIDE the publish
            // guard, exactly as in SearchViewModel — a superseded
            // load must not start its paging session over the new
            // load's tracker.
            if (isActive) {
                fetched?.let { continuationTracker.start(it.continuation) }
                _state.value = result
                _hasMore.value = continuationTracker.current != null
            }
        }
    }

    /** Appends the next page of tracks; a no-op at the true end or while one is loading. */
    fun loadMore() {
        val token = continuationTracker.current ?: return
        if (loadingMore) return
        if ((_state.value as? UiState.Content)?.data == null) return
        loadingMore = true
        _loadMoreError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val chunk = client.playlistContinuation(token, auth = null)
                // Round 5: the advance goes through the tracker's
                // guard, so a refresh that reset the tracker while
                // this fetch was out leaves the new session
                // untouched and this stale chunk drops here.
                if (!continuationTracker.tryAdvance(token, chunk.continuation)) {
                    return@launch
                }
                val nextToken = continuationTracker.current
                if (isActive) {
                    val latest = (_state.value as? UiState.Content)?.data
                    if (latest != null && latest.continuation == token) {
                        val fresh = dev.cyberdogs.offleash.core.model.newTracksOnly(
                            latest.tracks,
                            chunk.tracks,
                        )
                        _state.value = UiState.Content(
                            latest.copy(
                                tracks = latest.tracks + fresh,
                                continuation = nextToken,
                            )
                        )
                    }
                    _hasMore.value = continuationTracker.current != null
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isActive) {
                    _loadMoreError.value = ErrorMessages.userMessage(e)
                }
            } finally {
                loadingMore = false
            }
        }
    }
}

@Composable
fun PlaylistScreen(
    container: AppContainer,
    browseId: String,
    params: String?,
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
) {
    val viewModel: PlaylistViewModel = viewModel(key = browseId + (params ?: "")) {
        PlaylistViewModel(
            container.client,
            browseId,
            params,
        )
    }
    val state by viewModel.state.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val loadMoreError by viewModel.loadMoreError.collectAsState()
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
            val trackActions = rememberTrackActions(container, onAlbumClick, onArtistClick)
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    DetailHeader(
                        title = page.title,
                        meta = meta,
                        description = null,
                        thumbnailUrl = page.thumbnailUrl,
                        onPlayAll = { playAll(container, page.tracks) },
                        onShuffle = { shuffleAll(container, page.tracks) },
                        actionsEnabled = page.tracks.isNotEmpty(),
                    )
                }
                if (page.tracks.isEmpty()) {
                    item {
                        Text(
                            text = "No tracks were returned for this playlist.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                trackItems(
                    tracks = page.tracks,
                    container = container,
                    playlists = playlists,
                    scope = scope,
                    trackActions = trackActions,
                )
                if (loadMoreError != null) {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                text = loadMoreError!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { viewModel.loadMore() }) {
                                Text("Try again")
                            }
                        }
                    }
                } else if (hasMore) {
                    item {
                        OutlinedButton(
                            onClick = { viewModel.loadMore() },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text("Load more tracks")
                        }
                    }
                }
            }
        }
    }
}
