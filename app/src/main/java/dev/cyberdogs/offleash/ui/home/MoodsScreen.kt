package dev.cyberdogs.offleash.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cyberdogs.offleash.AppContainer
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.model.MoodCategory
import dev.cyberdogs.offleash.core.model.Section
import dev.cyberdogs.offleash.ui.ErrorMessages
import dev.cyberdogs.offleash.ui.components.ErrorPanel
import dev.cyberdogs.offleash.ui.components.LoadingPanel
import dev.cyberdogs.offleash.ui.components.SectionRow
import dev.cyberdogs.offleash.ui.components.UiState
import dev.cyberdogs.offleash.ui.components.rememberFeedActions
import dev.cyberdogs.offleash.ui.components.rememberTrackActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MoodsViewModel(private val client: YoutubeiClient) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<MoodCategory>>>(UiState.Loading)
    val state: StateFlow<UiState<List<MoodCategory>>> = _state

    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        // Stale-load guard (bug sweep round 1): see ChartsViewModel.
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                UiState.Content(client.moods())
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
fun MoodsScreen(
    onCategoryClick: (MoodCategory) -> Unit,
    container: AppContainer,
) {
    val viewModel: MoodsViewModel = viewModel { MoodsViewModel(container.client) }
    val state by viewModel.state.collectAsState()

    when (val current = state) {
        is UiState.Loading -> LoadingPanel()
        is UiState.Error -> ErrorPanel(message = current.message, onRetry = viewModel::load)
        is UiState.Content -> if (current.data.isEmpty()) {
            Text(
                text = "No moods or genres to show right now.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(current.data) { category ->
                    Card(
                        onClick = { onCategoryClick(category) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Text(
                            text = category.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

class MoodPlaylistsViewModel(
    private val client: YoutubeiClient,
    private val category: MoodCategory,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Section>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Section>>> = _state

    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        // Stale-load guard (bug sweep round 1): see ChartsViewModel.
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                UiState.Content(client.moodPlaylists(category))
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

/**
 * One mood/genre category page: the sections behind the category browse.
 * Playlists here navigate to playlist detail by their own browseId.
 */
@Composable
fun MoodPlaylistsScreen(
    container: AppContainer,
    params: String,
    title: String,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String, String?) -> Unit,
) {
    // The category's browseId is the MoodCategory default
    // (FEmusic_moods_and_genres_category) — the category is reached
    // by browseId + the category's params. This screen used to
    // override that default with an explicitly EMPTY id (bug sweep
    // round 1), so every mood page browsed nothing and could only
    // ever fail or return the wrong surface; the navigation route
    // carries just params and title, so the default is the correct
    // id by construction.
    val category = MoodCategory(title = title, params = params)
    val viewModel: MoodPlaylistsViewModel =
        viewModel(key = params) { MoodPlaylistsViewModel(container.client, category) }
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
        is UiState.Content -> LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
            if (current.data.isEmpty()) {
                item {
                    Text(
                        text = "No playlists to show for this mood right now.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            items(current.data) { section ->
                SectionRow(section = section, actions = actions)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
