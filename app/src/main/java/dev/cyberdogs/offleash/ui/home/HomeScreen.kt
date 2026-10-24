package dev.cyberdogs.offleash.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cyberdogs.offleash.AppContainer
import dev.cyberdogs.offleash.core.api.AuthSession
import dev.cyberdogs.offleash.core.api.YoutubeiClient
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

/**
 * Home feed. Signed out, this is exactly the Phase 1 anonymous feed
 * (`client.home()` with no session). Signed in, the feed is loaded with
 * the current session (`client.home(auth)`) so YouTube serves the
 * personalized home. The screen observes the session state and reloads
 * whenever sign-in state flips.
 */
class HomeViewModel(
    private val client: YoutubeiClient,
    private val sessionProvider: () -> AuthSession? = { null },
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Section>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Section>>> = _state

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val auth = sessionProvider()
            _state.value = try {
                UiState.Content(if (auth != null) client.home(auth) else client.home())
            } catch (e: Exception) {
                UiState.Error(ErrorMessages.userMessage(e))
            }
        }
    }
}

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenCharts: () -> Unit,
    onOpenMoods: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String, String?) -> Unit,
) {
    val viewModel: HomeViewModel = viewModel {
        HomeViewModel(container.client) { container.sessionManager.currentAuthSession() }
    }
    val state by viewModel.state.collectAsState()
    val sessionState by container.sessionManager.uiState.collectAsState()

    // Load on first composition and reload on every sign-in/out
    // transition, so the feed matches the current session.
    LaunchedEffect(sessionState.signedIn) {
        viewModel.load()
    }

    val actions = rememberFeedActions(
        queueController = container.queueController,
        playlistStore = container.localPlaylistStore,
        onAlbumClick = onAlbumClick,
        onArtistClick = onArtistClick,
        onPlaylistClick = onPlaylistClick,
    )

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onOpenCharts, modifier = Modifier.weight(1f)) {
                Text("Charts")
            }
            Button(onClick = onOpenMoods, modifier = Modifier.weight(1f)) {
                Text("Moods & genres")
            }
        }
        when (val current = state) {
            is UiState.Loading -> LoadingPanel()
            is UiState.Error -> ErrorPanel(message = current.message, onRetry = viewModel::load)
            is UiState.Content -> LazyColumn(Modifier.fillMaxSize()) {
                items(current.data) { section ->
                    SectionRow(section = section, actions = actions)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
