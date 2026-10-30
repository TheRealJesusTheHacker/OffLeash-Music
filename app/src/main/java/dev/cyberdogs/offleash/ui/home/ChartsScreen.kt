package dev.cyberdogs.offleash.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

class ChartsViewModel(private val client: YoutubeiClient) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Section>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Section>>> = _state

    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        // Stale-load guard (bug sweep round 1): cancel the previous
        // load and publish only while this job is still active — the
        // client call is blocking, so a superseded load otherwise
        // overwrites the newer state when it finally returns.
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                UiState.Content(client.charts())
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
fun ChartsScreen(
    container: AppContainer,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String, String?) -> Unit,
) {
    val viewModel: ChartsViewModel = viewModel { ChartsViewModel(container.client) }
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
            if (current.data.isEmpty()) {
                item {
                    Text(
                        text = "No charts to show right now.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
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
