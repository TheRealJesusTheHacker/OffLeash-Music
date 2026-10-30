package dev.cyberdogs.offleash.ui.components

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.cyberdogs.offleash.AppContainer
import dev.cyberdogs.offleash.core.model.LocalPlaylist
import dev.cyberdogs.offleash.core.model.Track
import dev.cyberdogs.offleash.data.LikeController
import dev.cyberdogs.offleash.playback.likeActionLabel
import dev.cyberdogs.offleash.playback.shareTextFor

/**
 * The non-queue actions a track menu needs, bundled once (Lane G).
 * Every surface that lists tracks builds one of these — via
 * [rememberTrackActions] where an [AppContainer] is at hand — so the
 * menu vocabulary is identical everywhere a track appears.
 */
class TrackActions(
    val onStartRadio: (Track) -> Unit,
    val onArtistClick: (browseId: String) -> Unit,
    val onAlbumClick: (browseId: String) -> Unit,
    /** Deep-links to the full player, where the sleep-timer and speed controls live. */
    val onShowPlayer: () -> Unit,
    val likeController: LikeController?,
    /** Offline download (parity step 2): enqueues the track on the download manager. */
    val onDownload: (Track) -> Unit,
    /**
     * The download manager, when one is at hand: the menu reads its
     * records so the Download item names the track's real state
     * instead of offering a tap that silently does nothing.
     */
    val downloadManager: dev.cyberdogs.offleash.data.DownloadManager? = null,
)

/** Builds the standard [TrackActions] for a screen from the container. */
@Composable
fun rememberTrackActions(
    container: AppContainer,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onShowPlayer: () -> Unit = { container.requestShowPlayer() },
): TrackActions = remember(container, onAlbumClick, onArtistClick, onShowPlayer) {
    TrackActions(
        onStartRadio = { track -> container.queueController.startRadio(track) },
        onArtistClick = onArtistClick,
        onAlbumClick = onAlbumClick,
        onShowPlayer = onShowPlayer,
        likeController = container.likeController,
        onDownload = { track -> container.downloadManager.enqueue(track) },
        downloadManager = container.downloadManager,
    )
}

/**
 * Android share sheet for a track: a plain ACTION_SEND carrying the
 * track's YouTube Music URL (see `shareTextFor`). No SDK, no custom
 * share target.
 */
fun shareTrack(context: android.content.Context, track: Track) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, shareTextFor(track))
        putExtra(Intent.EXTRA_SUBJECT, track.title)
    }
    context.startActivity(
        Intent.createChooser(send, "Share track").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}

/**
 * The full track-menu vocabulary behind one overflow button:
 * Like, Play next, Add to queue, Add to playlist…, Start radio,
 * Artists / Album (only when the track actually carries the browse
 * id), Sleep timer and Playback speed & pitch (both deep-link to the
 * player controls via [TrackActions.onShowPlayer]), and Share.
 */
@Composable
fun TrackOverflowMenu(
    track: Track,
    actions: TrackActions,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    playlists: List<LocalPlaylist>,
    onAddToPlaylist: (playlist: LocalPlaylist, track: Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }

    val likeController = actions.likeController

    Box(modifier = modifier) {
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More options for ${track.title}",
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (likeController != null) {
                LikeMenuItem(
                    likeController = likeController,
                    track = track,
                    onPicked = { menuOpen = false },
                )
            }
            DropdownMenuItem(
                text = { Text("Play next") },
                onClick = {
                    menuOpen = false
                    onPlayNext(track)
                },
            )
            DropdownMenuItem(
                text = { Text("Add to queue") },
                onClick = {
                    menuOpen = false
                    onAddToQueue(track)
                },
            )
            DropdownMenuItem(
                text = { Text("Add to playlist…") },
                onClick = {
                    menuOpen = false
                    pickerOpen = true
                },
            )
            if (actions.downloadManager != null) {
                DownloadMenuItem(
                    downloadManager = actions.downloadManager!!,
                    track = track,
                    onDownload = {
                        menuOpen = false
                        actions.onDownload(track)
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Download") },
                    onClick = {
                        menuOpen = false
                        actions.onDownload(track)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Start radio") },
                onClick = {
                    menuOpen = false
                    actions.onStartRadio(track)
                },
            )
            track.artistBrowseId?.takeIf { it.isNotBlank() }?.let { artistId ->
                DropdownMenuItem(
                    text = { Text("Artist") },
                    onClick = {
                        menuOpen = false
                        actions.onArtistClick(artistId)
                    },
                )
            }
            track.albumBrowseId?.takeIf { it.isNotBlank() }?.let { albumId ->
                DropdownMenuItem(
                    text = { Text("Album") },
                    onClick = {
                        menuOpen = false
                        actions.onAlbumClick(albumId)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Sleep timer") },
                onClick = {
                    menuOpen = false
                    actions.onShowPlayer()
                },
            )
            DropdownMenuItem(
                text = { Text("Playback speed & pitch") },
                onClick = {
                    menuOpen = false
                    actions.onShowPlayer()
                },
            )
            DropdownMenuItem(
                text = { Text("Share") },
                onClick = {
                    menuOpen = false
                    shareTrack(context, track)
                },
            )
        }
    }

    if (pickerOpen) {
        PlaylistPickerDialog(
            playlists = playlists,
            onPick = { playlist ->
                pickerOpen = false
                onAddToPlaylist(playlist, track)
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/**
 * The Download menu row: the label names the track's live download
 * state. Enqueueing an already-downloaded (or in-flight) track is a
 * silent no-op in the engine, so an always-on "Download" label was a
 * dead-feeling tap for exactly the tracks a user re-checks; a failed
 * download offers the retry the Library's Downloads section offers.
 */
@Composable
private fun DownloadMenuItem(
    downloadManager: dev.cyberdogs.offleash.data.DownloadManager,
    track: Track,
    onDownload: () -> Unit,
) {
    val records by downloadManager.records.collectAsState()
    val state = records.firstOrNull { it.videoId == track.videoId }?.state
    when (state) {
        dev.cyberdogs.offleash.data.DownloadState.DONE -> DropdownMenuItem(
            text = { Text("Downloaded") },
            enabled = false,
            onClick = {},
        )
        // QUEUED is not DOWNLOADING (bug sweep round 5): a queued
        // track waits behind the one-at-a-time engine — possibly
        // behind dozens of auto-downloads — and the Library's own
        // status line calls that state "Waiting to download". The
        // menu lumped both states into "Downloading…", claiming an
        // activity that had not started for exactly the tracks
        // still waiting.
        dev.cyberdogs.offleash.data.DownloadState.QUEUED -> DropdownMenuItem(
            text = { Text("Waiting to download") },
            enabled = false,
            onClick = {},
        )
        dev.cyberdogs.offleash.data.DownloadState.DOWNLOADING -> DropdownMenuItem(
            text = { Text("Downloading…") },
            enabled = false,
            onClick = {},
        )
        dev.cyberdogs.offleash.data.DownloadState.FAILED -> DropdownMenuItem(
            text = { Text("Retry download") },
            onClick = onDownload,
        )
        else -> DropdownMenuItem(
            text = { Text("Download") },
            onClick = onDownload,
        )
    }
}

/** The Like menu row: label and icon follow the track's live like state. */
@Composable
private fun LikeMenuItem(
    likeController: LikeController,
    track: Track,
    onPicked: () -> Unit,
) {
    val likeStates by likeController.states.collectAsState()
    val accountBacked by likeController.accountBacked.collectAsState()
    val likeState = likeStates[track.videoId]
        ?: dev.cyberdogs.offleash.playback.LikeUiState(
            liked = false,
            accountBacked = accountBacked,
        )
    DropdownMenuItem(
        text = { Text(likeActionLabel(likeState)) },
        leadingIcon = {
            Icon(
                imageVector = if (likeState.liked) {
                    Icons.Default.Favorite
                } else {
                    Icons.Default.FavoriteBorder
                },
                contentDescription = null,
            )
        },
        onClick = {
            onPicked()
            likeController.toggle(track)
        },
    )
}
