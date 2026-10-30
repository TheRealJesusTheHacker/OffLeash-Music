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
import dev.cyberdogs.offleash.core.api.BrowseDelegation
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

/**
 * The note shown over the anonymous feed when the personalized
 * feed refused (0.8.2): the degradation is honest, never silent —
 * the owner can tell at a glance that what he is seeing is the
 * public catalog, not his feed.
 */
const val PERSONALIZED_FEED_REFUSED_NOTE: String =
    "Your personalized feed was refused — showing the public feed."

class HomeViewModel(
    private val client: YoutubeiClient,
    private val sessionProvider: () -> AuthSession? = { null },
    /**
     * The Google OAuth access token (0.7.0), read on the load's IO
     * thread. When present, the feed is the token session's home —
     * ahead of the cookie session's, matching the rest of the app.
     */
    private val bearerProvider: () -> String? = { null },
    /**
     * The stored brand delegation (0.8.2), read beside the bearer:
     * the personalized browse runs as the account's brand channel
     * when one was captured at sign-in.
     */
    private val delegationProvider: () -> BrowseDelegation? = { null },
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<HomeFeed>>(UiState.Loading)
    val state: StateFlow<UiState<HomeFeed>> = _state

    /**
     * The degradation note (0.8.2): non-null exactly when the feed
     * on screen is the ANONYMOUS feed served because the
     * personalized one refused. A signed-in user must never reach
     * less music than a signed-out one — the refusal degrades to
     * the public catalog under this honest note, never to a blank.
     */
    private val _feedNote = MutableStateFlow<String?>(null)
    val feedNote: StateFlow<String?> = _feedNote

    private val _selectedChip = MutableStateFlow<HomeChip?>(null)
    val selectedChip: StateFlow<HomeChip?> = _selectedChip

    private var loadJob: kotlinx.coroutines.Job? = null

    fun load() = loadFeed(chip = null, carryChips = false)

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
        // response.
        //
        // The carry is scoped to chip re-browses (bug sweep round
        // 30): a full load() is a NEW feed — the first load, an
        // ErrorPanel retry, and above all the identity-keyed reload
        // HomeScreen fires whenever sign-in state or the account's
        // email changes. The chips are browse endpoints (label +
        // browseId + params) minted by the PREVIOUS response; on an
        // account switch that never passes through a landed
        // signed-out feed, carrying them let the previous account's
        // chip row render as the new account's, and tapping one
        // browsed the new account's home with the old account's
        // endpoint. A full load therefore starts from the new
        // response's own chips alone (an Error state holds no
        // Content, so the retry path loses nothing by this).
        val keptChips = if (carryChips) {
            (_state.value as? UiState.Content)?.data?.chips
        } else {
            null
        }
        _selectedChip.value = chip
        _feedNote.value = null
        _state.value = UiState.Loading
        // Stale-load guard (bug sweep round 1, the SearchViewModel
        // rule applied here): the client call is blocking, so a
        // superseded load — the anonymous feed still in flight when
        // sign-in completes, a slow chip re-browse overtaken by a
        // newer tap — runs to completion on its IO thread. Cancelling
        // the previous job and publishing only while this job is
        // still active keeps the OLD response from overwriting the
        // NEWER state (signed-in home showing anonymous results was
        // exactly this race).
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val auth = sessionProvider()
            var note: String? = null
            val result = try {
                val bearer = bearerProvider()
                val feed = if (bearer != null) {
                    try {
                        client.homeFeedOAuth(bearer, chip, delegationProvider())
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        // Degradation (0.8.2): the personalized
                        // cascade refused — fall back to the
                        // anonymous feed (cookie session when one
                        // stands, plain anonymous otherwise) under
                        // the honest note. Only when THAT also
                        // fails does the load become an error.
                        note = PERSONALIZED_FEED_REFUSED_NOTE
                        client.homeFeed(auth, chip)
                    }
                } else {
                    client.homeFeed(auth, chip)
                }
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
                // The note publishes with the content it describes,
                // inside the same stale-load guard: a superseded
                // degraded load must not leave its note standing
                // over a newer personalized feed.
                if (result is UiState.Content) _feedNote.value = note
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
        HomeViewModel(
            container.client,
            sessionProvider = { container.sessionManager.currentAuthSession() },
            bearerProvider = { container.currentBearerToken() },
            delegationProvider = { container.currentBrowseDelegation() },
        )
    }
    val state by viewModel.state.collectAsState()
    val feedNote by viewModel.feedNote.collectAsState()
    val selectedChip by viewModel.selectedChip.collectAsState()
    val sessionState by container.accountUiState.collectAsState()

    // Load on first composition and reload on every sign-in/out
    // transition, so the feed matches the current session. The
    // identity is part of the key (bug sweep round 1): switching
    // accounts without an intervening signed-out state changes the
    // email but not `signedIn`, and the previous account's
    // personalized feed must not survive that switch.
    LaunchedEffect(sessionState.signedIn, sessionState.identityEmail) {
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
                    if (feedNote != null) {
                        item {
                            Text(
                                text = feedNote!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    horizontal = 16.dp,
                                    vertical = 8.dp,
                                ),
                            )
                        }
                    }
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
