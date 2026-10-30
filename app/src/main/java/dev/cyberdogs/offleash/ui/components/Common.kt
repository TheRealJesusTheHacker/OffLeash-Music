package dev.cyberdogs.offleash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.cyberdogs.offleash.core.model.FeedItem
import dev.cyberdogs.offleash.core.model.LocalPlaylist
import dev.cyberdogs.offleash.core.model.Section
import dev.cyberdogs.offleash.core.model.Track
import kotlinx.coroutines.launch

/** Loading / Content / Error state shared by every data screen. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Content<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

/** Navigation + queue actions the feed components need, bundled once. */
class FeedActions(
    val onTrackClick: (tracks: List<Track>, index: Int) -> Unit,
    val onAlbumClick: (browseId: String) -> Unit,
    val onArtistClick: (browseId: String) -> Unit,
    val onPlaylistClick: (browseId: String, params: String?) -> Unit,
    val onPlayNext: (Track) -> Unit,
    val onAddToQueue: (Track) -> Unit,
    val playlists: List<LocalPlaylist>,
    val onAddToPlaylist: (playlist: LocalPlaylist, track: Track) -> Unit,
    /** Lane G: the full track-menu vocabulary (like / radio / share / …). */
    val trackActions: TrackActions,
)

fun formatDuration(totalSeconds: Int?): String {
    if (totalSeconds == null || totalSeconds < 0) return ""
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** Coil artwork with a surface-colored placeholder while loading / on miss. */
@Composable
fun ArtworkImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    playlists: List<LocalPlaylist>,
    onAddToPlaylist: (playlist: LocalPlaylist, track: Track) -> Unit,
    trackActions: TrackActions,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            url = track.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.size(52.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val artistLine = track.artists.joinToString(", ")
            val duration = formatDuration(track.durationSeconds)
            val subtitle = when {
                artistLine.isNotEmpty() && duration.isNotEmpty() -> "$artistLine • $duration"
                artistLine.isNotEmpty() -> artistLine
                else -> duration
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            trailing()
        }
        TrackOverflowMenu(
            track = track,
            actions = trackActions,
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            playlists = playlists,
            onAddToPlaylist = onAddToPlaylist,
        )
    }
}

@Composable
fun PlaylistPickerDialog(
    playlists: List<LocalPlaylist>,
    onPick: (LocalPlaylist) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            // The list is height-bounded and scrolls: a dialog's text
            // area does not scroll on its own, so a long playlist
            // list pushed its lower rows past the dialog's edge —
            // visible in the layout, unreachable by tap (the same
            // overflow class as the 0.6.6 sign-in fix).
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (playlists.isEmpty()) {
                    Text("No playlists yet. Create one from the Library tab.")
                } else {
                    playlists.forEach { playlist ->
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(playlist) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun FeedCard(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.width(148.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            ArtworkImage(
                url = thumbnailUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * One feed section: an optional title, a horizontal card row for
 * album/artist/playlist items, and a vertical track list for tracks.
 * Track taps report the section's flattened track list + tapped index so
 * playback starts with the whole section as the queue.
 */
@Composable
fun SectionRow(
    section: Section,
    actions: FeedActions,
    modifier: Modifier = Modifier,
) {
    val tracks = section.items.filterIsInstance<FeedItem.OfTrack>().map { it.track }
    val cards = section.items.filterNot { it is FeedItem.OfTrack }

    Column(modifier = modifier.fillMaxWidth()) {
        val title = section.title
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (cards.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(cards) { item ->
                    when (item) {
                        is FeedItem.OfAlbum -> FeedCard(
                            title = item.album.title,
                            subtitle = item.album.artist ?: "",
                            thumbnailUrl = item.album.thumbnailUrl,
                            onClick = { actions.onAlbumClick(item.album.browseId) },
                        )
                        is FeedItem.OfArtist -> FeedCard(
                            title = item.artist.name,
                            subtitle = item.artist.detail ?: "",
                            thumbnailUrl = item.artist.thumbnailUrl,
                            onClick = { actions.onArtistClick(item.artist.browseId) },
                        )
                        is FeedItem.OfPlaylist -> FeedCard(
                            title = item.playlist.title,
                            subtitle = item.playlist.author ?: "",
                            thumbnailUrl = item.playlist.thumbnailUrl,
                            onClick = { actions.onPlaylistClick(item.playlist.browseId, null) },
                        )
                        is FeedItem.OfTrack -> Unit // handled below as rows
                    }
                }
            }
        }
        tracks.forEachIndexed { index, track ->
            TrackRow(
                track = track,
                onClick = { actions.onTrackClick(tracks, index) },
                onPlayNext = actions.onPlayNext,
                onAddToQueue = actions.onAddToQueue,
                playlists = actions.playlists,
                onAddToPlaylist = actions.onAddToPlaylist,
                trackActions = actions.trackActions,
            )
        }
    }
}

@Composable
fun ErrorPanel(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    // Scrollable: a long message (the both-roads refusal is three
    // sentences) in a short viewport — landscape, or a large font
    // scale — overflowed this centered, non-scrolling column and
    // clipped the Try again button it exists to offer. The scroll
    // keeps every part of the panel reachable (the 0.6.6 standard).
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

/**
 * Builds the standard [FeedActions] for a screen: track taps play through
 * the queue controller, card taps navigate, playlist adds persist.
 */
@Composable
fun rememberFeedActions(
    queueController: dev.cyberdogs.offleash.playback.QueueController,
    playlistStore: dev.cyberdogs.offleash.data.LocalPlaylistStore,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String, String?) -> Unit,
    trackActions: TrackActions,
): FeedActions {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val playlists by playlistStore.playlists
        .collectAsStateWithLifecycle()
    return remember(playlists, queueController, playlistStore, trackActions) {
        FeedActions(
            onTrackClick = { tracks, index -> queueController.playTracks(tracks, index) },
            onAlbumClick = onAlbumClick,
            onArtistClick = onArtistClick,
            onPlaylistClick = onPlaylistClick,
            onPlayNext = { track -> queueController.playNext(track) },
            onAddToQueue = { track -> queueController.addToQueue(track) },
            playlists = playlists,
            onAddToPlaylist = { playlist, track ->
                scope.launch { playlistStore.addTrack(playlist.id, track) }
            },
            trackActions = trackActions,
        )
    }
}

@Composable
fun LoadingPanel(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}
