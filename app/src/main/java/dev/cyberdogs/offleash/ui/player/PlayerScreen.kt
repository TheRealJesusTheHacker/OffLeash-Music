package dev.cyberdogs.offleash.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cyberdogs.offleash.AppContainer
import dev.cyberdogs.offleash.core.playback.RepeatMode
import dev.cyberdogs.offleash.playback.showLoadingIndicator
import dev.cyberdogs.offleash.ui.components.ArtworkImage
import dev.cyberdogs.offleash.ui.components.BatteryRestrictionCard
import dev.cyberdogs.offleash.ui.components.TrackRow
import dev.cyberdogs.offleash.ui.components.formatDuration
import kotlinx.coroutines.launch

private fun formatMillis(ms: Long): String {
    if (ms < 0) return "0:00"
    return formatDuration((ms / 1000).toInt())
}

/**
 * Full player overlay. Bound entirely to QueueController's StateFlows;
 * the seek slider commits on release so polling never fights the finger.
 */
@Composable
fun PlayerScreen(
    container: AppContainer,
    onClose: () -> Unit,
) {
    val queueState by container.queueController.queueState.collectAsState()
    val playbackState by container.queueController.playbackState.collectAsState()
    val namedError by container.queueController.namedError.collectAsState()

    var showQueue by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableStateOf(0f) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        if (showQueue) {
            QueueScreen(
                container = container,
                onBack = { showQueue = false },
            )
            return@Surface
        }

        val track = queueState.currentTrack
        Column(
            modifier = Modifier
                .fillMaxSize()
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
        }
    }
}

/** The current queue: tap a track to jump to it; the playing row is lit. */
@Composable
fun QueueScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val queueState by container.queueController.queueState.collectAsState()
    val playlists by container.localPlaylistStore.playlists.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
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
                        isCurrent = index == queueState.currentIndex,
                    )
                }
            }
        }
    }
}
