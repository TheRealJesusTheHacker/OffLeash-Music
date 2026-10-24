package dev.cyberdogs.offleash.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.model.HomeChip
import dev.cyberdogs.offleash.core.model.HomeFeed
import dev.cyberdogs.offleash.ui.ErrorMessages
import dev.cyberdogs.offleash.ui.components.ErrorPanel
import dev.cyberdogs.offleash.ui.components.LoadingPanel
import dev.cyberdogs.offleash.ui.components.SectionRow
import dev.cyberdogs.offleash.ui.components.UiState
import dev.cyberdogs.offleash.ui.components.rememberFeedActions
import dev.cyberdogs.offleash.ui.components.rememberTrackActions
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Home feed. Signed out, this is exactly the Phase 1 anonymous feed —
 * same browse, same sections, no added chrome. Signed in, the feed is
 * loaded with the current session (`client.homeFeed(auth)`) so YouTube
 * serves the personalized home, and the signed-in chrome (Lane H)
 * renders on top of it: a time-of-day greeting with the account name,
 * the response's own mood chips (tapping one re-browses home with the
 * chip's endpoint; tapping it again returns to the full feed), a
 * Liked Music card into the `VLLM` playlist surface, and every titled
 * section the parser returned — including "Listen again" /
 * "Forgotten favorites" rails when the response carries them
 * (classified by [classifyHomeSection], rendered by the same
 * [SectionRow] rail as every section; a section the API didn't return
 * is never rendered). The screen observes the session state and
 * reloads whenever sign-in state flips.
 */
class HomeViewModel(
    private val client: YoutubeiClient,
    private val sessionProvider: () -> AuthSession? = { null },
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<HomeFeed>>(UiState.Loading)
    val state: StateFlow<UiState<HomeFeed>> = _state

    private val _selectedChip = MutableStateFlow<HomeChip?>(null)
    val selectedChip: StateFlow<HomeChip?> = _selectedChip

    fun load() = loadFeed(chip = null)

    /** Tapping the selected chip again clears it back to the full feed. */
    fun onChipClick(chip: HomeChip) {
        if (_selectedChip.value == chip) {
            loadFeed(chip = null)
        } else {
            loadFeed(chip = chip)
        }
    }

    private fun loadFeed(chip: HomeChip?) {
        // Captured BEFORE the Loading reset: a filtered re-browse
        // that carries no chip cloud of its own keeps the full feed's
        // chips on screen, so the row the user is interacting with
        // never vanishes mid-tap; sections always come from the new
        // response.
        val keptChips = (_state.value as? UiState.Content)?.data?.chips
        _selectedChip.value = chip
        _state.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val auth = sessionProvider()
            _state.value = try {
                val feed = client.homeFeed(auth, chip)
                UiState.Content(
                    if (feed.chips.isEmpty() && !keptChips.isNullOrEmpty()) {
                        feed.copy(chips = keptChips)
                    } else {
                        feed
                    },
                )
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
    val selectedChip by viewModel.selectedChip.collectAsState()
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
        trackActions = rememberTrackActions(container, onAlbumClick, onArtistClick),
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
            is UiState.Content -> {
                val chrome = homeChromeState(
                    signedIn = sessionState.signedIn,
                    identityName = sessionState.identityName,
                    identityEmail = sessionState.identityEmail,
                    chips = current.data.chips,
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    if (chrome.showGreeting) {
                        item {
                            Text(
                                text = greetingForHour(
                                    LocalTime.now().hour,
                                    chrome.displayName,
                                ),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(
                                    horizontal = 16.dp,
                                    vertical = 8.dp,
                                ),
                            )
                        }
                    }
                    if (chrome.chips.isNotEmpty()) {
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(chrome.chips) { chip ->
                                    FilterChip(
                                        selected = chip == selectedChip,
                                        onClick = { viewModel.onChipClick(chip) },
                                        label = { Text(chip.label) },
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    if (chrome.showLikedMusicCard) {
                        item {
                            Card(
                                onClick = { onPlaylistClick(LIKED_MUSIC_BROWSE_ID, null) },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(
                                        text = "Liked Music",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "Your liked songs, from your account",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    itemsIndexed(current.data.sections) { _, section ->
                        // Personalized rails ("Listen again",
                        // "Forgotten favorites" — classifyHomeSection)
                        // render through the same SectionRow as every
                        // titled section, in API order; the kind is
                        // pinned by HomeChromeTest.
                        SectionRow(section = section, actions = actions)
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}
