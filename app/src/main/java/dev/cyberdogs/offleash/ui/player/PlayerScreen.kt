package dev.cyberdogs.offleash.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cyberdogs.offleash.AppContainer
import dev.cyberdogs.offleash.core.model.LyricsLine
import dev.cyberdogs.offleash.core.model.LyricsResult
import dev.cyberdogs.offleash.core.playback.RepeatMode
import dev.cyberdogs.offleash.playback.LyricsCache
import dev.cyberdogs.offleash.playback.PLAYBACK_SPEED_STEPS
import dev.cyberdogs.offleash.playback.SLEEP_TIMER_MINUTE_OPTIONS
import dev.cyberdogs.offleash.playback.currentLyricsIndex
import dev.cyberdogs.offleash.playback.formatPlaybackSpeed
import dev.cyberdogs.offleash.playback.formatSleepTimerRemaining
import dev.cyberdogs.offleash.playback.lyricsAreSynced
import dev.cyberdogs.offleash.playback.showLoadingIndicator
import dev.cyberdogs.offleash.ui.components.ArtworkImage
import dev.cyberdogs.offleash.ui.components.BatteryRestrictionCard
import dev.cyberdogs.offleash.ui.components.TrackRow
import dev.cyberdogs.offleash.ui.components.formatDuration
import dev.cyberdogs.offleash.ui.components.rememberTrackActions
import dev.cyberdogs.offleash.ui.components.shareTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun formatMillis(ms: Long): String {
    if (ms < 0) return "0:00"
    return formatDuration((ms / 1000).toInt())
}

/** Process-lifetime in-memory lyrics cache, per track (Lane G). */
private val playerLyricsCache = LyricsCache()

/** What the lyrics section of the player renders for the current track. */
private sealed interface LyricsUiState {
    data object Loading : LyricsUiState
    data class Found(
        val lines: List<LyricsLine>,
        val source: String,
        val synced: Boolean,
    ) : LyricsUiState
    data class Unavailable(val reason: String) : LyricsUiState
}

/**
 * Full player overlay. Bound entirely to QueueController's StateFlows;
 * the seek slider commits on release so polling never fights the finger.
 */
@Composable
fun PlayerScreen(
    container: AppContainer,
    onClose: () -> Unit,
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
) {
    val queueState by container.queueController.queueState.collectAsState()
    val playbackState by container.queueController.playbackState.collectAsState()
    val namedError by container.queueController.namedError.collectAsState()
    val sleepTimerState by container.queueController.sleepTimerState.collectAsState()
    val sleepTimerCountdown by container.queueController.sleepTimerCountdownMs.collectAsState()
    val speedState by container.queueController.speedState.collectAsState()
    val likeStates by container.likeController.states.collectAsState()
    val context = LocalContext.current

    var showQueue by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableStateOf(0f) }
    var lyricsState by remember { mutableStateOf<LyricsUiState?>(null) }

    val currentTrack = queueState.currentTrack

    // Lyrics + account like status for the current track. Both are
    // async decorations: neither blocks playback, and a lyrics miss
    // is a named state, never a blank panel.
    LaunchedEffect(currentTrack?.videoId) {
        val track = currentTrack
        if (track == null) {
            lyricsState = null
        } else {
            container.likeController.refreshAccountStatus(track)
            val cached = playerLyricsCache[track.videoId]
            if (cached != null) {
                lyricsState = cached.toLyricsUiState()
            } else {
                lyricsState = LyricsUiState.Loading
                val result = withContext(Dispatchers.IO) {
                    val synced = container.client.syncedLyrics(
                        track.title,
                        track.artists.firstOrNull() ?: "",
                        track.durationSeconds,
                    )
                    if (synced != null) {
                        LyricsResult.Found(synced, source = "LRCLIB")
                    } else {
                        container.client.lyrics(
                            track.videoId,
                            container.sessionManager.currentAuthSession(),
                        )
                    }
                }
                playerLyricsCache.put(track.videoId, result)
                // Publish only if this track is still the current
                // one (bug sweep round 9; premise settled round 11):
                // the fetch above sits inside this effect's single
                // withContext(IO), so when a skip cancels the effect
                // the withContext throws CancellationException at
                // its boundary and a stale effect's cache put and
                // publish never execute at all — as constructed,
                // the stale overwrite cannot occur, and this guard
                // is defence-in-depth, not the load-bearing half.
                // It earns its place against the day the fetch is
                // moved out of the cancellable boundary or the
                // publish is reached from a path cancellation
                // cannot see. The cache put above is keyed by
                // videoId and stays either way. The read is the
                // controller's LIVE queue state, not this effect's
                // captured snapshot.
                if (container.queueController.queueState.value
                        .currentTrack?.videoId == track.videoId
                ) {
                    lyricsState = result.toLyricsUiState()
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        if (showQueue) {
            QueueScreen(
                container = container,
                onBack = { showQueue = false },
                onAlbumClick = onAlbumClick,
                onArtistClick = onArtistClick,
            )
            return@Surface
        }

        val track = queueState.currentTrack
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The player is a full-window overlay (no Scaffold
                // insets reach it) running under transient system
                // bars: safeDrawing sizes the content inside the
                // bars whenever they are shown or revealed, and is
                // zero while they are hidden, so the player is
                // full-bleed immersive AND no control ever sits
                // under a bar (2026-10-27 bars pass).
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BatteryRestrictionCard()
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close player",
                    )
                }
                Text(
                    text = "Now playing",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = { showQueue = true }) {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = "Show queue",
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            ArtworkImage(
                url = track?.thumbnailUrl,
                contentDescription = track?.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
            Spacer(Modifier.height(24.dp))

            Text(
                text = track?.title ?: "Nothing playing",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track?.artists?.joinToString(", ") ?: "",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (namedError != null) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.error)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = namedError ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { container.queueController.retryCurrent() }) {
                        Text("Try again", color = MaterialTheme.colorScheme.onError)
                    }
                    TextButton(onClick = { container.queueController.clearError() }) {
                        Text("Dismiss", color = MaterialTheme.colorScheme.onError)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            val durationMs = playbackState.durationMs
            val sliderValue = when {
                scrubbing -> scrubValue
                durationMs > 0 -> playbackState.positionMs.toFloat() / durationMs.toFloat()
                else -> 0f
            }
            Slider(
                value = sliderValue.coerceIn(0f, 1f),
                onValueChange = { value ->
                    scrubbing = true
                    scrubValue = value
                },
                onValueChangeFinished = {
                    if (durationMs > 0) {
                        container.queueController.seekTo((scrubValue * durationMs).toLong())
                    }
                    scrubbing = false
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatMillis(
                        if (scrubbing && durationMs > 0) {
                            (scrubValue * durationMs).toLong()
                        } else {
                            playbackState.positionMs
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatMillis(durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { container.queueController.toggleShuffle() }) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (queueState.isShuffled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = { container.queueController.previous() }) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous track",
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(
                    onClick = { container.queueController.togglePlayPause() },
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) {
                            Icons.Default.Pause
                        } else {
                            Icons.Default.PlayArrow
                        },
                        contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(48.dp),
                    )
                }
                IconButton(onClick = { container.queueController.next() }) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next track",
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = { container.queueController.cycleRepeat() }) {
                    Icon(
                        imageVector = if (queueState.repeatMode == RepeatMode.ONE) {
                            Icons.Default.RepeatOne
                        } else {
                            Icons.Default.Repeat
                        },
                        contentDescription = "Repeat: ${queueState.repeatMode.name.lowercase()}",
                        tint = if (queueState.repeatMode != RepeatMode.OFF) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            if (showLoadingIndicator(playbackState.isResolving, playbackState.isBuffering)) {
                // Resolving (any road / ladder step) or buffering before
                // READY: name the wait instead of stalling silently.
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = "Loading…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(onClick = { showQueue = true }) {
                Icon(imageVector = Icons.Default.QueueMusic, contentDescription = null)
                Text("  Queue")
            }

            // -- Lane G: like / radio / share for the current track --
            if (track != null) {
                Spacer(Modifier.height(16.dp))
                val likeState = likeStates[track.videoId]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { container.likeController.toggle(track) }) {
                        Icon(
                            imageVector = if (likeState?.liked == true) {
                                Icons.Default.Favorite
                            } else {
                                Icons.Default.FavoriteBorder
                            },
                            contentDescription = if (likeState?.liked == true) {
                                "Remove like"
                            } else {
                                "Like"
                            },
                            tint = if (likeState?.liked == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = { container.queueController.startRadio(track) }) {
                        Icon(
                            imageVector = Icons.Default.Radio,
                            contentDescription = "Start radio",
                        )
                    }
                    IconButton(onClick = { shareTrack(context, track) }) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share track",
                        )
                    }
                }
            }

            // -- Lane G: sleep timer --
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(imageVector = Icons.Default.Bedtime, contentDescription = null)
                Text(
                    text = "  Sleep timer",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !sleepTimerState.isArmed,
                    onClick = { container.queueController.cancelSleepTimer() },
                    label = { Text("Off") },
                )
                SLEEP_TIMER_MINUTE_OPTIONS.take(3).forEach { minutes ->
                    FilterChip(
                        selected = sleepTimerState.requestedMinutes == minutes &&
                            sleepTimerState.deadlineMs != null,
                        onClick = { container.queueController.setSleepTimerMinutes(minutes) },
                        label = { Text("$minutes min") },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SLEEP_TIMER_MINUTE_OPTIONS.drop(3).forEach { minutes ->
                    FilterChip(
                        selected = sleepTimerState.requestedMinutes == minutes &&
                            sleepTimerState.deadlineMs != null,
                        onClick = { container.queueController.setSleepTimerMinutes(minutes) },
                        label = { Text("$minutes min") },
                    )
                }
                FilterChip(
                    selected = sleepTimerState.endOfTrack,
                    onClick = { container.queueController.setSleepTimerEndOfTrack() },
                    label = { Text("End of track") },
                )
            }
            if (sleepTimerState.isArmed) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        sleepTimerState.endOfTrack ->
                            "Playback stops at the end of this track."
                        sleepTimerCountdown != null ->
                            "Playback pauses in " +
                                formatSleepTimerRemaining(sleepTimerCountdown ?: 0L) + "."
                        else -> "Sleep timer is on."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // -- Lane G: playback speed & pitch --
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(imageVector = Icons.Default.Speed, contentDescription = null)
                Text(
                    text = "  Speed & pitch",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PLAYBACK_SPEED_STEPS.take(4).forEach { step ->
                    FilterChip(
                        selected = speedState.speed == step,
                        onClick = { container.queueController.setPlaybackSpeed(step) },
                        label = { Text(formatPlaybackSpeed(step)) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PLAYBACK_SPEED_STEPS.drop(4).forEach { step ->
                    FilterChip(
                        selected = speedState.speed == step,
                        onClick = { container.queueController.setPlaybackSpeed(step) },
                        label = { Text(formatPlaybackSpeed(step)) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Keep pitch at speed",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = speedState.preservePitch,
                    onCheckedChange = { container.queueController.setPreservePitch(it) },
                )
            }
            Text(
                text = if (speedState.preservePitch) {
                    "Pitch stays put while the tempo changes."
                } else {
                    "Pitch rises and falls with the tempo."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )

            // -- Lane G: lyrics --
            Spacer(Modifier.height(20.dp))
            LyricsSection(
                state = lyricsState,
                positionMs = playbackState.positionMs,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun LyricsResult.toLyricsUiState(): LyricsUiState = when (this) {
    is LyricsResult.Found -> LyricsUiState.Found(
        lines = lines,
        source = source,
        synced = lyricsAreSynced(lines),
    )
    is LyricsResult.Unavailable -> LyricsUiState.Unavailable(reason)
}

/**
 * The lyrics panel: synced lines highlight the current line by
 * playback position and auto-scroll to it; plain lyrics list
 * statically with no line claimed current; no source at all renders
 * the NAMED unavailable state — never a blank panel.
 */
@Composable
private fun LyricsSection(state: LyricsUiState?, positionMs: Long) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "Lyrics",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        when (val current = state) {
            null -> Text(
                text = "Play a track to see its lyrics.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is LyricsUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Text(
                    text = "  Loading lyrics…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is LyricsUiState.Unavailable -> Text(
                // Core hands this panel two reason shapes: a
                // fragment ("YouTube offers no lyrics for this
                // track", wrapped below) and a complete sentence
                // ("Lyrics are unavailable for this track.",
                // period included). Wrapping the sentence too
                // rendered "Lyrics unavailable — Lyrics are
                // unavailable for this track.." — a complete
                // sentence stands alone (bug sweep round 66).
                text = if (current.reason.endsWith(".")) {
                    current.reason
                } else {
                    "Lyrics unavailable — ${current.reason}."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is LyricsUiState.Found -> {
                Text(
                    text = if (current.synced) {
                        "Synced lyrics • ${current.source}"
                    } else {
                        "Lyrics • ${current.source}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val currentIndex = if (current.synced) {
                    currentLyricsIndex(current.lines, positionMs)
                } else {
                    -1
                }
                val listState = rememberLazyListState()
                LaunchedEffect(currentIndex) {
                    if (currentIndex > 0) {
                        listState.animateScrollToItem(
                            (currentIndex - 1).coerceAtLeast(0),
                        )
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                ) {
                    itemsIndexed(current.lines) { index, line ->
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (index == currentIndex) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                            color = if (index == currentIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The current queue: tap a track to jump to it; the playing row is lit. */
@Composable
fun QueueScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
) {
    val queueState by container.queueController.queueState.collectAsState()
    val playlists by container.localPlaylistStore.playlists.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val trackActions = rememberTrackActions(
        container = container,
        onAlbumClick = onAlbumClick,
        onArtistClick = onArtistClick,
        // Already on the player: the deep-link just returns to it.
        onShowPlayer = onBack,
    )

    Column(
        Modifier
            .fillMaxSize()
            // Same overlay discipline as the player it lives in:
            // content sized inside the system bars whenever they
            // are shown or transiently revealed (2026-10-27 bars
            // pass).
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Back to player")
            }
            Text(
                text = "Queue",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (queueState.tracks.isEmpty()) {
            Text(
                text = "The queue is empty.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(queueState.tracks) { index, track ->
                    TrackRow(
                        track = track,
                        onClick = { container.queueController.playQueueItem(index) },
                        onPlayNext = { container.queueController.playNext(it) },
                        onAddToQueue = { container.queueController.addToQueue(it) },
                        playlists = playlists,
                        onAddToPlaylist = { playlist, picked ->
                            scope.launch {
                                container.localPlaylistStore.addTrack(playlist.id, picked)
                            }
                        },
                        trackActions = trackActions,
                        isCurrent = index == queueState.currentIndex,
                    )
                }
            }
        }
    }
}
