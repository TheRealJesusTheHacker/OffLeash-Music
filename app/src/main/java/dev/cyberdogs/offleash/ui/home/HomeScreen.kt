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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Home feed (0.9.0): the anonymous YouTube Music feed — the
 * music source — with OffLeash's own chrome on top of it: a
 * time-of-day greeting, the response's own filter chips (tapping
 * one re-browses home with the chip's endpoint; tapping it again
 * returns to the full feed), and every titled section the parser
 * returned, in order. A section the API didn't return is never
 * rendered, and no part of this screen consults an account.
 */

class HomeViewModel(
    private val client: YoutubeiClient,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<HomeFeed>>(UiState.Loading)
    val state: StateFlow<UiState<HomeFeed>> = _state

    private val _selectedChip = MutableStateFlow<HomeChip?>(null)
    val selectedChip: StateFlow<HomeChip?> = _selectedChip

    private var loadJob: kotlinx.coroutines.Job? = null

    fun load() {
        loadFeed(chip = null, carryChips = false)
    }

    /** Tapping the selected chip again clears it back to the full feed. */
    fun onChipClick(chip: HomeChip) {
        if (_selectedChip.value == chip) {
            loadFeed(chip = null)
        } else {
            loadFeed(chip = chip)
        }
    }

    private fun loadFeed(chip: HomeChip?, carryChips: Boolean = true) {
        // Captured BEFORE the Loading reset: a filtered re-browse
        // that carries no chip cloud of its own keeps the full feed's
        // chips on screen, so the row the user is interacting with
        // never vanishes mid-tap; sections always come from the new
        // response. A full load() starts from the new response's own
        // chips alone.
        val keptChips = if (carryChips) {
            (_state.value as? UiState.Content)?.data?.chips
        } else {
            null
        }
        _selectedChip.value = chip
        _state.value = UiState.Loading
        // Stale-load guard (bug sweep round 1, the SearchViewModel
        // rule applied here): the client call is blocking, so a
        // superseded load runs to completion on its IO thread.
        // Cancelling the previous job and publishing only while this
        // job is still active keeps the OLD response from
        // overwriting the NEWER state.
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                val feed = client.homeFeed(null, chip)
                UiState.Content(
                    if (feed.chips.isEmpty() && !keptChips.isNullOrEmpty()) {
                        feed.copy(chips = keptChips)
                    } else {
                        feed
                    },
                )
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
fun HomeScreen(
    container: AppContainer,
    onOpenCharts: () -> Unit,
    onOpenMoods: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String, String?) -> Unit,
) {
    val viewModel: HomeViewModel = viewModel {
        HomeViewModel(container.client)
    }
    val state by viewModel.state.collectAsState()
    val selectedChip by viewModel.selectedChip.collectAsState()

    // Load on first composition; the feed is anonymous, so there
    // is no session state to reload on.
    LaunchedEffect(Unit) {
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
                val chrome = homeChromeState(chips = current.data.chips)
                LazyColumn(Modifier.fillMaxSize()) {
                    if (chrome.showGreeting) {
                        item {
                            Text(
                                text = greetingForHour(LocalTime.now().hour),
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
                    // The empty state keys on the sections alone
                    // (round 20): chips are navigation chrome, not
                    // content — a signed-in feed (or a chip browse)
                    // that returns zero sections must still SAY it
                    // came back empty, instead of leaving a silent
                    // blank under the greeting and chip row. Every
                    // sibling browse surface (Charts, Moods, Search)
                    // names its empty result the same way.
                    if (current.data.sections.isEmpty()) {
                        item {
                            Text(
                                text = "Nothing to show right now. Try again in a bit.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    itemsIndexed(current.data.sections) { _, section ->
                        // Feed rails ("Listen again",
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
