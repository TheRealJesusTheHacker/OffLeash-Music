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
import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.api.BrowseDelegation
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.model.AlbumPage
import dev.cyberdogs.offleash.core.model.ArtistPage
import dev.cyberdogs.offleash.core.model.PlaylistPage
import dev.cyberdogs.offleash.core.model.Track
import dev.cyberdogs.offleash.ui.ErrorMessages
import dev.cyberdogs.offleash.ui.home.LIKED_MUSIC_BROWSE_ID
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
    /**
     * The Google OAuth access token (0.7.0), read on the load's IO
     * thread. When present, the playlist loads under the token
     * session — this is the road the Library's playlists and Liked
     * Songs take.
     */
    private val bearerProvider: () -> String? = { null },
    /**
     * The stored brand delegation (0.8.2), read beside the bearer:
     * account playlists (Liked included) live on the brand channel.
     */
    private val delegationProvider: () -> BrowseDelegation? = { null },
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
                val bearer = bearerProvider()
                val page = if (bearer != null) {
                    try {
                        client.playlistOAuth(
                            bearer, browseId, params, delegationProvider(),
                        )
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        // Degradation (0.8.2): the token road
                        // refused — the cookie/anonymous road still
                        // serves PUBLIC playlists, so a refused
                        // account must not blank a page the public
                        // can open. One dishonest shape is guarded:
                        // the anonymous Liked page (VLLM) is an
                        // empty shell (200, zero tracks), so an
                        // empty VLLM fallback rethrows the token
                        // road's refusal instead of presenting a
                        // fake-empty Liked list.
                        val fallback = client.playlist(
                            browseId, params, auth = authProvider(),
                        )
                        if (browseId == "VLLM" && fallback.tracks.isEmpty()) throw e
                        fallback
                    }
                } else {
                    client.playlist(browseId, params, auth = authProvider())
                }
                fetched = page
                UiState.Content(page)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                UiState.Error(ErrorMessages.userMessage(e))
            }
            // Round 5: the tracker start lives INSIDE the publish
            // guard, exactly as in SearchViewModel. It used to run
            // in the try above, so a superseded (cancelled) load —
            // the client call is blocking and runs to completion —
            // that returned after a refresh had started its own
            // paging session still called start() with the STALE
            // page's token, clobbering the new session's tracker
            // while _state correctly stayed new. The refreshed
            // page's Load more then fetched the stale session's
            // pages and its merge guard refused them all: a dead
            // Load more, hasMore stuck on.
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
                val bearer = bearerProvider()
                val chunk = if (bearer != null) {
                    client.playlistContinuationOAuth(bearer, token, delegationProvider())
                } else {
                    client.playlistContinuation(token, auth = authProvider())
                }
                // Round 5: this job is NOT the load job — a
                // refresh cannot cancel it — and the fetch is
                // blocking, so its isActive stays true even when
                // its paging session is long gone. The advance goes
                // through the tracker's guard: when a refresh
                // reset/re-started the tracker while this fetch was
                // out, tryAdvance refuses and leaves the new session
                // untouched, and this stale chunk drops here
                // instead of clobbering the new session's current
                // token (the merge guard below reads state that
                // the unguarded advance had already corrupted).
                if (!continuationTracker.tryAdvance(token, chunk.continuation)) {
                    return@launch
                }
                val nextToken = continuationTracker.current
                if (isActive) {
                    // Merge against the LATEST page, not the one
                    // captured at tap time: a refresh that landed
                    // while this chunk was in flight keeps its own
                    // first page, and this chunk appends nothing to
                    // a page it no longer belongs to (its tracker was
                    // reset by the refresh, ending paging above).
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
                // The loaded page stays on screen; only the "more"
                // row reports the failure, with its own retry.
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
            authProvider = { container.sessionManager.currentAuthSession() },
            bearerProvider = { container.currentBearerToken() },
            delegationProvider = { container.currentBrowseDelegation() },
        )
    }
    val state by viewModel.state.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val loadMoreError by viewModel.loadMoreError.collectAsState()
    val sessionState by container.accountUiState.collectAsState()

    // Reload when sign-in state flips after the initial load, so an
    // account playlist (e.g. Liked Songs) opened signed-out picks up the
    // session once the user signs in — and drops it on sign-out.
    // The identity is part of the key (bug sweep round 1): an
    // account switch that never passes through signed-out must also
    // reload, or the previous account's page survives the switch.
    var authEffectSeen by remember { mutableStateOf(false) }
    LaunchedEffect(sessionState.signedIn, sessionState.identityEmail) {
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
            val trackActions = rememberTrackActions(container, onAlbumClick, onArtistClick)
            // Round 36: the Liked Songs page IS the account's like
            // list — every track on it is account-liked by
            // construction. Seed the like controller with that
            // fact (it fills only tracks it holds no state for),
            // so a row's heart and menu offer "Remove like" from
            // the start instead of defaulting to "Like" until the
            // track happens to become the player's current track
            // and a per-track status fetch lands.
            LaunchedEffect(page.tracks, browseId) {
                page.tracks
                    .takeIf {
                        browseId == LIKED_MUSIC_BROWSE_ID && sessionState.signedIn
                    }
                    ?.let { container.likeController.noteAccountLiked(it) }
            }
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
