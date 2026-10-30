package dev.cyberdogs.offleash.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cyberdogs.offleash.AppContainer
import dev.cyberdogs.offleash.core.api.SearchFilters
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.model.FeedItem
import dev.cyberdogs.offleash.core.model.SearchFilter
import dev.cyberdogs.offleash.core.model.SearchResults
import dev.cyberdogs.offleash.ui.ErrorMessages
import dev.cyberdogs.offleash.ui.components.ErrorPanel
import dev.cyberdogs.offleash.ui.components.LoadingPanel
import dev.cyberdogs.offleash.ui.components.SectionRow
import dev.cyberdogs.offleash.ui.components.rememberFeedActions
import dev.cyberdogs.offleash.ui.components.rememberTrackActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState

    /**
     * A landed search: the results AND the query they were actually
     * searched for (round 19). The screen's empty-state label names
     * this query, not the live text field — the field moves ahead
     * of the search by the debounce (and keeps moving while the
     * user types), so naming it would attribute an empty result to
     * a query that was never searched, including the empty string
     * in the moments after the field is cleared.
     */
    data class Content(val results: SearchResults, val query: String) : SearchUiState
    data class Error(val message: String) : SearchUiState
}

class SearchViewModel(private val client: YoutubeiClient) : ViewModel() {

    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state

    private var searchJob: Job? = null
    private var lastQuery: String = ""
    private var lastFilter: SearchFilter = SearchFilters.ALL

    // Continuation paging (bug sweep round 1): same discipline as
    // the playlist page — the tracker ends paging on an absent or
    // repeated token, and a fresh search resets it.
    private val continuationTracker =
        dev.cyberdogs.offleash.core.model.ContinuationTracker()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore

    private val _loadMoreError = MutableStateFlow<String?>(null)
    val loadMoreError: StateFlow<String?> = _loadMoreError

    private var loadingMore = false

    fun search(query: String, filter: SearchFilter) {
        val trimmed = query.trim()
        lastQuery = trimmed
        lastFilter = filter
        searchJob?.cancel()
        continuationTracker.reset()
        _hasMore.value = false
        _loadMoreError.value = null
        if (trimmed.isEmpty()) {
            _state.value = SearchUiState.Idle
            return
        }
        _state.value = SearchUiState.Loading
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            var fetched: SearchResults? = null
            val result = try {
                val results = client.search(trimmed, filter)
                fetched = results
                SearchUiState.Content(results, trimmed)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SearchUiState.Error(ErrorMessages.userMessage(e))
            }
            // Sweep #5: client.search is a BLOCKING call, so
            // cancelling this job (a newer search superseding this
            // one) cannot interrupt it — the call runs to completion
            // on its IO thread regardless. Publishing unconditionally
            // here let a slow superseded search overwrite the newer
            // search's state with stale results. A cancelled job
            // publishes nothing; the current job's own result is the
            // only one that may land.
            //
            // Round 5: the tracker start lives INSIDE the same
            // guard. It used to run in the try above — before the
            // guard — so a superseded search that returned after
            // the newer search had started its own paging session
            // still called start() with the STALE token, clobbering
            // the new session's tracker (and marking the stale
            // token seen) while _state correctly stayed new. The
            // new search's Load more then fetched the old search's
            // pages and its merge guard refused them all: a dead
            // Load more button, hasMore stuck on, forever.
            if (isActive) {
                fetched?.let { continuationTracker.start(it.continuation) }
                _state.value = result
                _hasMore.value = continuationTracker.current != null
            }
        }
    }

    /** Appends the next page of results; a no-op at the true end or while one is loading. */
    fun loadMore() {
        val token = continuationTracker.current ?: return
        if (loadingMore) return
        val current = (_state.value as? SearchUiState.Content)?.results ?: return
        loadingMore = true
        _loadMoreError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val chunk = client.searchContinuation(token)
                // Round 5: this job is NOT the search job — a newer
                // search cannot cancel it — and the fetch is
                // blocking, so its isActive stays true even when its
                // paging session is long gone. The advance therefore
                // goes through the tracker's guard: when a newer
                // search reset/re-started the tracker while this
                // fetch was out, tryAdvance refuses and leaves the
                // new session untouched, and this stale chunk drops
                // here instead of clobbering the new session's
                // current token (the merge guard below reads state
                // that the unguarded advance had already corrupted).
                if (!continuationTracker.tryAdvance(token, chunk.continuation)) {
                    return@launch
                }
                val nextToken = continuationTracker.current
                if (isActive) {
                    // Merge against the latest results (a newer
                    // search may have landed meanwhile; its reset
                    // tracker already ended this paging session).
                    // The merged state keeps the landed search's own
                    // query (round 19): it is still that search's
                    // result list, however many pages it grows by.
                    val latestContent = _state.value as? SearchUiState.Content
                    val latest = latestContent?.results
                    if (latest != null && latestContent != null && latest.continuation == token) {
                        _state.value = SearchUiState.Content(
                            latest.copy(
                                sections = latest.sections + chunk.sections,
                                continuation = nextToken,
                            ),
                            latestContent.query,
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

    fun retry() {
        if (lastQuery.isNotEmpty()) search(lastQuery, lastFilter)
    }
}

@Composable
fun SearchScreen(
    container: AppContainer,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String, String?) -> Unit,
) {
    val viewModel: SearchViewModel = viewModel { SearchViewModel(container.client) }
    val state by viewModel.state.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val loadMoreError by viewModel.loadMoreError.collectAsState()

    var query by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(SearchFilters.ALL) }
    val focusManager = LocalFocusManager.current

    // Debounce-lite: re-search shortly after the query or filter changes.
    LaunchedEffect(query, selectedFilter) {
        kotlinx.coroutines.delay(400)
        viewModel.search(query, selectedFilter)
    }

    val baseActions = rememberFeedActions(
        queueController = container.queueController,
        playlistStore = container.localPlaylistStore,
        onAlbumClick = onAlbumClick,
        onArtistClick = onArtistClick,
        onPlaylistClick = onPlaylistClick,
        trackActions = rememberTrackActions(container, onAlbumClick, onArtistClick),
    )

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search YouTube Music") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    viewModel.search(query, selectedFilter)
                    // An explicit submit is the listener saying
                    // "show me the results": drop the focus so the
                    // keyboard dismisses and the results below are
                    // visible (bug sweep round 52). Typing stays on
                    // the debounce path and never clears focus.
                    focusManager.clearFocus()
                },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )

        // Filter chips: prefer the filters the API itself published in the
        // last results; fall back to the pinned set before any search.
        val publishedFilters = (state as? SearchUiState.Content)?.results?.filters
        val filters = if (publishedFilters.isNullOrEmpty()) {
            SearchFilters.ALL_FILTERS
        } else {
            publishedFilters
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filters) { filter ->
                FilterChip(
                    selected = filter.params == selectedFilter.params,
                    onClick = { selectedFilter = filter },
                    label = { Text(filter.label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        when (val current = state) {
            is SearchUiState.Idle -> {
                Text(
                    text = "Search for songs, albums, artists and playlists.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            is SearchUiState.Loading -> LoadingPanel()
            is SearchUiState.Error -> ErrorPanel(message = current.message, onRetry = viewModel::retry)
            is SearchUiState.Content -> {
                if (current.results.sections.isEmpty()) {
                    Text(
                        text = "No results for \"${current.query}\".",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    // Track taps play within the flattened song list across
                    // all result sections, in display order: SectionRow
                    // reports the tapped section's tracks, and we map the
                    // tap onto the flattened list so next/previous move
                    // through every result.
                    val flatTracks = current.results.sections
                        .flatMap { it.items }
                        .filterIsInstance<FeedItem.OfTrack>()
                        .map { it.track }
                    val flattenedActions = remember(baseActions, flatTracks) {
                        dev.cyberdogs.offleash.ui.components.FeedActions(
                            onTrackClick = { sectionTracks, sectionIndex ->
                                val tapped = sectionTracks.getOrNull(sectionIndex)
                                val startIndex = flatTracks.indexOfFirst {
                                    it.videoId == tapped?.videoId
                                }
                                if (flatTracks.isNotEmpty() && startIndex >= 0) {
                                    container.queueController.playTracks(
                                        flatTracks,
                                        startIndex,
                                    )
                                } else if (sectionTracks.isNotEmpty()) {
                                    container.queueController.playTracks(
                                        sectionTracks,
                                        sectionIndex.coerceIn(sectionTracks.indices),
                                    )
                                }
                            },
                            onAlbumClick = baseActions.onAlbumClick,
                            onArtistClick = baseActions.onArtistClick,
                            onPlaylistClick = baseActions.onPlaylistClick,
                            onPlayNext = baseActions.onPlayNext,
                            onAddToQueue = baseActions.onAddToQueue,
                            playlists = baseActions.playlists,
                            onAddToPlaylist = baseActions.onAddToPlaylist,
                            trackActions = baseActions.trackActions,
                        )
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(current.results.sections) { section ->
                            SectionRow(section = section, actions = flattenedActions)
                            Spacer(Modifier.height(16.dp))
                        }
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
                                    Text("Load more results")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
