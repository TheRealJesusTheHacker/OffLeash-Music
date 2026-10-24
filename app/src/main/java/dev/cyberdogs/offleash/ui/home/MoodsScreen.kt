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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MoodsViewModel(private val client: YoutubeiClient) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<MoodCategory>>>(UiState.Loading)
    val state: StateFlow<UiState<List<MoodCategory>>> = _state

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                UiState.Content(client.moods())
            } catch (e: Exception) {
                UiState.Error(ErrorMessages.userMessage(e))
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
        is UiState.Content -> LazyVerticalGrid(
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

class MoodPlaylistsViewModel(
    private val client: YoutubeiClient,
    private val category: MoodCategory,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Section>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Section>>> = _state

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                UiState.Content(client.moodPlaylists(category))
            } catch (e: Exception) {
                UiState.Error(ErrorMessages.userMessage(e))
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
    val category = MoodCategory(title = title, params = params, browseId = "")
    val viewModel: MoodPlaylistsViewModel =
        viewModel(key = params) { MoodPlaylistsViewModel(container.client, category) }
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
            items(current.data) { section ->
                SectionRow(section = section, actions = actions)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
