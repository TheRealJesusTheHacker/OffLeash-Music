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
    data class Content(val results: SearchResults) : SearchUiState
    data class Error(val message: String) : SearchUiState
}

class SearchViewModel(private val client: YoutubeiClient) : ViewModel() {

    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state

    private var searchJob: Job? = null
    private var lastQuery: String = ""
    private var lastFilter: SearchFilter = SearchFilters.ALL

    fun search(query: String, filter: SearchFilter) {
        val trimmed = query.trim()
        lastQuery = trimmed
        lastFilter = filter
        searchJob?.cancel()
        if (trimmed.isEmpty()) {
            _state.value = SearchUiState.Idle
            return
        }
        _state.value = SearchUiState.Loading
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                SearchUiState.Content(client.search(trimmed, filter))
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
            if (isActive) {
                _state.value = result
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

    var query by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(SearchFilters.ALL) }

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
                onSearch = { viewModel.search(query, selectedFilter) },
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
                        text = "No results for \"$query\".",
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
                    }
                }
            }
        }
    }
}
