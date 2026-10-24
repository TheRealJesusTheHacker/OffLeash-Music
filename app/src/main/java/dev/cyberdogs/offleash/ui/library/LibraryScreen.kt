package dev.cyberdogs.offleash.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cyberdogs.offleash.AppContainer
import dev.cyberdogs.offleash.core.model.LocalPlaylist
import dev.cyberdogs.offleash.core.model.PlaylistSummary
import dev.cyberdogs.offleash.ui.ErrorMessages
import dev.cyberdogs.offleash.ui.components.ArtworkImage
import dev.cyberdogs.offleash.ui.components.TrackRow
import dev.cyberdogs.offleash.ui.components.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    container: AppContainer,
    onPlaylistClick: (String) -> Unit,
    onSignInClick: () -> Unit = {},
    onRemotePlaylistClick: (String, String?) -> Unit = { _, _ -> },
) {
    val playlists by container.localPlaylistStore.playlists.collectAsState()
    val recents by container.recentsStore.recents().collectAsState()
    val scope = rememberCoroutineScope()
    val sessionState by container.sessionManager.uiState.collectAsState()

    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<LocalPlaylist?>(null) }
    var deleteTarget by remember { mutableStateOf<LocalPlaylist?>(null) }

    // "Your YouTube library" (Phase 2): the account's playlists, loaded
    // with the current session whenever signed in. Reloads on sign-in/out
    // transitions and on an explicit retry.
    var ytPlaylists by remember {
        mutableStateOf<UiState<List<PlaylistSummary>>>(UiState.Loading)
    }
    var ytReloadTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(sessionState.signedIn, ytReloadTick) {
        if (!sessionState.signedIn) return@LaunchedEffect
        ytPlaylists = UiState.Loading
        val auth = container.sessionManager.currentAuthSession()
        ytPlaylists = if (auth == null) {
            UiState.Error("Sign in again to load your YouTube library.")
        } else {
            try {
                UiState.Content(
                    withContext(Dispatchers.IO) { container.client.libraryPlaylists(auth) },
                )
            } catch (e: Exception) {
                UiState.Error(ErrorMessages.userMessage(e))
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        // -- Your YouTube library (signed in) / sign-in card (signed out) --
        if (sessionState.signedIn) {
            item {
                Text(
                    text = "Your YouTube library",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRemotePlaylistClick("VLLM", null) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtworkImage(
                        url = null,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Liked songs",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "From your YouTube account",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Text(
                    text = "Playlists from your account",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            when (val yt = ytPlaylists) {
                is UiState.Loading -> item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Loading your playlists…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is UiState.Error -> item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = yt.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { ytReloadTick++ }) {
                            Text("Try again")
                        }
                    }
                }
                is UiState.Content -> {
                    if (yt.data.isEmpty()) {
                        item {
                            Text(
                                text = "No playlists on your account yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                    items(yt.data) { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onRemotePlaylistClick(playlist.browseId, null)
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ArtworkImage(
                                url = playlist.thumbnailUrl,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = playlist.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val subtitle = listOfNotNull(
                                    playlist.author?.takeIf { it.isNotBlank() },
                                    playlist.trackCount?.let { "$it tracks" },
                                ).joinToString(" • ")
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
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        } else {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "Sign in to see your liked songs and playlists",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Your YouTube library lives on your account. " +
                                "Signed-out use stays fully anonymous.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onSignInClick) { Text("Sign in") }
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Your playlists",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Button(onClick = { showCreate = true }) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Text("New")
                }
            }
        }
        if (playlists.isEmpty()) {
            item {
                Text(
                    text = "No playlists yet. Make one — it stays on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        items(playlists) { playlist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlaylistClick(playlist.id) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(
                    url = playlist.tracks.firstOrNull()?.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${playlist.tracks.size} tracks",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { renameTarget = playlist }) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Rename ${playlist.name}",
                    )
                }
                IconButton(onClick = { deleteTarget = playlist }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete ${playlist.name}",
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Recently played",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (recents.isEmpty()) {
            item {
                Text(
                    text = "Nothing played yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        itemsIndexed(recents) { index, track ->
            TrackRow(
                track = track,
                onClick = { container.queueController.playTracks(recents, index) },
                onPlayNext = { container.queueController.playNext(it) },
                onAddToQueue = { container.queueController.addToQueue(it) },
                playlists = playlists,
                onAddToPlaylist = { playlist, picked ->
                    scope.launch { container.localPlaylistStore.addTrack(playlist.id, picked) }
                },
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    if (showCreate) {
        PlaylistNameDialog(
            title = "New playlist",
            initialName = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                showCreate = false
                if (name.isNotBlank()) {
                    scope.launch { container.localPlaylistStore.createPlaylist(name) }
                }
            },
            onDismiss = { showCreate = false },
        )
    }

    renameTarget?.let { target ->
        PlaylistNameDialog(
            title = "Rename playlist",
            initialName = target.name,
            confirmLabel = "Rename",
            onConfirm = { name ->
                renameTarget = null
                if (name.isNotBlank()) {
                    scope.launch { container.localPlaylistStore.renamePlaylist(target.id, name) }
                }
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete playlist?") },
            text = { Text("\"${target.name}\" and its track list will be removed from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch { container.localPlaylistStore.deletePlaylist(target.id) }
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Playlist name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun LocalPlaylistScreen(
    container: AppContainer,
    playlistId: String,
) {
    val playlists by container.localPlaylistStore.playlists.collectAsState()
    val scope = rememberCoroutineScope()
    val playlist = playlists.firstOrNull { it.id == playlistId }

    if (playlist == null) {
        Text(
            text = "This playlist no longer exists.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${playlist.tracks.size} tracks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            if (playlist.tracks.isNotEmpty()) {
                                container.queueController.playTracks(playlist.tracks, 0)
                            }
                        },
                        enabled = playlist.tracks.isNotEmpty(),
                    ) { Text("Play all") }
                    OutlinedButton(
                        onClick = {
                            if (playlist.tracks.isNotEmpty()) {
                                container.queueController.setShuffle(true)
                                container.queueController.playTracks(playlist.tracks, 0)
                            }
                        },
                        enabled = playlist.tracks.isNotEmpty(),
                    ) { Text("Shuffle") }
                }
            }
        }
        if (playlist.tracks.isEmpty()) {
            item {
                Text(
                    text = "No tracks yet. Add some from any track's menu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        itemsIndexed(playlist.tracks, key = { _, track -> track.videoId }) { index, track ->
            TrackRow(
                track = track,
                onClick = { container.queueController.playTracks(playlist.tracks, index) },
                onPlayNext = { container.queueController.playNext(it) },
                onAddToQueue = { container.queueController.addToQueue(it) },
                playlists = playlists,
                onAddToPlaylist = { target, picked ->
                    scope.launch { container.localPlaylistStore.addTrack(target.id, picked) }
                },
                trailing = {
                    Row {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    container.localPlaylistStore.moveTrack(
                                        playlist.id,
                                        index,
                                        index - 1,
                                    )
                                }
                            },
                            enabled = index > 0,
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = "Move ${track.title} up",
                            )
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    container.localPlaylistStore.moveTrack(
                                        playlist.id,
                                        index,
                                        index + 1,
                                    )
                                }
                            },
                            enabled = index < playlist.tracks.lastIndex,
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Move ${track.title} down",
                            )
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    container.localPlaylistStore.removeTrack(
                                        playlist.id,
                                        track.videoId,
                                    )
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Remove ${track.title}",
                            )
                        }
                    }
                },
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
