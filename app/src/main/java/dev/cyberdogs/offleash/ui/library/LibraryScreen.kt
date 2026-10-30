package dev.cyberdogs.offleash.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import dev.cyberdogs.offleash.AppContainer
import dev.cyberdogs.offleash.account.AutoDownloadLiked
import dev.cyberdogs.offleash.core.model.LocalPlaylist
import dev.cyberdogs.offleash.core.model.PlaylistSummary
import dev.cyberdogs.offleash.data.DownloadRecord
import dev.cyberdogs.offleash.data.DownloadState
import dev.cyberdogs.offleash.data.downloadProgressPercent
import dev.cyberdogs.offleash.data.formatDownloadBytes
import dev.cyberdogs.offleash.data.summariseDownloads
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
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
) {
    val trackActions = dev.cyberdogs.offleash.ui.components.rememberTrackActions(
        container, onAlbumClick, onArtistClick,
    )
    val playlists by container.localPlaylistStore.playlists.collectAsState()
    val recents by container.recentsStore.recents().collectAsState()
    val downloadRecords by container.downloadManager.records.collectAsState()
    val autoDownloadLiked by container.appPreferences.autoDownloadLikedFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val sessionState by container.accountUiState.collectAsState()

    // Auto-download of Liked (preference-gated, default off): when the
    // Library is opened signed-in with the preference on, ask the
    // WorkManager job to load the liked list and enqueue what's missing.
    LaunchedEffect(sessionState.signedIn, autoDownloadLiked) {
        if (sessionState.signedIn && autoDownloadLiked) {
            AutoDownloadLiked.enqueue(context)
        }
    }

    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<LocalPlaylist?>(null) }
    var deleteTarget by remember { mutableStateOf<LocalPlaylist?>(null) }
    var showClearDownloads by remember { mutableStateOf(false) }

    // "Your YouTube library" (Phase 2): the account's playlists, loaded
    // with the current session whenever signed in. Reloads on sign-in/out
    // transitions and on an explicit retry.
    var ytPlaylists by remember {
        mutableStateOf<UiState<List<PlaylistSummary>>>(UiState.Loading)
    }
    var ytReloadTick by remember { mutableIntStateOf(0) }
    // The identity is part of the key (bug sweep round 1): an
    // account switch that never passes through signed-out changes
    // the email but not `signedIn`, and the previous account's
    // playlists must not survive it. On sign-out the section below
    // stops rendering entirely, so no authed data stays on screen.
    LaunchedEffect(sessionState.signedIn, sessionState.identityEmail, ytReloadTick) {
        if (!sessionState.signedIn) return@LaunchedEffect
        ytPlaylists = UiState.Loading
        ytPlaylists = try {
            val loaded = withContext(Dispatchers.IO) {
                // The token road first (0.7.0): a Google session's
                // library comes from its Bearer token; the cookie
                // session is the fallback for a pre-0.7.0 sign-in.
                val bearer = container.currentBearerToken()
                when {
                    bearer != null -> container.client.libraryPlaylistsOAuth(
                        bearer,
                        delegation = container.currentBrowseDelegation(),
                    )
                    else -> container.sessionManager.currentAuthSession()
                        ?.let { container.client.libraryPlaylists(it) }
                }
            }
            if (loaded == null) {
                UiState.Error("Sign in again to load your YouTube library.")
            } else {
                UiState.Content(loaded)
            }
        } catch (e: Exception) {
            // A superseded load (identity flipped again mid-flight)
            // is cancelled by the effect relaunch; its cancellation
            // must not be recorded as an error state over the new
            // load's result.
            if (e is kotlinx.coroutines.CancellationException) throw e
            UiState.Error(ErrorMessages.userMessage(e))
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

        // -- Downloads (parity step 2): downloaded + queued tracks, with
        // honest storage accounting computed from the records and the
        // actual files — never a stored or guessed number.
        item {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Downloads",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val summary = summariseDownloads(downloadRecords) { record ->
                container.downloadManager.fileLength(record)
            }
            val summaryText = when {
                downloadRecords.isEmpty() -> null
                summary.downloadedCount > 0 ->
                    "${summary.downloadedCount} downloaded • " +
                        "${formatDownloadBytes(summary.totalBytes)} on this device"
                else -> "Nothing fully downloaded yet"
            }
            if (summaryText != null) {
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            // A download list that could not be read says so here,
            // where the owner is looking at it (bug sweep round 1 —
            // the store flags any loss, from a wholly unreadable
            // file down to one salvaged entry; until this notice
            // existed the flag had no reader and the loss was
            // silent).
            if (container.downloadStore.loadRecoveredFromCorruption) {
                Text(
                    // Wording covers BOTH losses the flag now means
                    // (round 4): the round-1 text said the list "was
                    // started fresh", which was true when the flag
                    // only meant a wholly unreadable file — but the
                    // round-2/3 salvage also sets it when a single
                    // entry (or a duplicate) was dropped and every
                    // other download survived, and telling the owner
                    // those downloads were gone was a lie of exactly
                    // the kind this notice exists to avoid.
                    text = "Your download list couldn't be read completely, " +
                        "so some downloads may be missing. You can download " +
                        "any track again from its menu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (downloadRecords.isNotEmpty()) {
                TextButton(
                    onClick = { showClearDownloads = true },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text("Clear all downloads") }
            }
        }
        if (downloadRecords.isEmpty()) {
            item {
                Text(
                    text = "No downloads yet. Download a track from its menu " +
                        "and it will play without a connection.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        items(downloadRecords, key = { it.videoId }) { record ->
            val doneTracks = downloadRecords
                .filter { it.state == DownloadState.DONE }
                .map { it.track }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = record.state == DownloadState.DONE) {
                        val index = doneTracks.indexOfFirst { it.videoId == record.videoId }
                        if (index >= 0) {
                            container.queueController.playTracks(doneTracks, index)
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(
                    url = record.track.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = record.track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = downloadStatusText(record, container.downloadManager.fileLength(record)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (record.state == DownloadState.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        // A FAILED reason is uncapped (round 64):
                        // this row is the ONLY place the reason is
                        // ever shown and a FAILED row is not
                        // tappable, so the shipped 2-line ellipsis
                        // cut the full reason — up to 300 stored
                        // chars — out of reach entirely. The row
                        // grows instead; every other state keeps
                        // the 2-line bound.
                        maxLines = if (record.state == DownloadState.FAILED) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (record.state == DownloadState.DOWNLOADING) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                if (record.state == DownloadState.FAILED) {
                    OutlinedButton(
                        onClick = { container.downloadManager.enqueue(record.track) },
                    ) { Text("Retry") }
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = { container.downloadManager.delete(record.videoId) }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete download of ${record.track.title}",
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
                trackActions = trackActions,
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
            text = {
                // Height-bounded and scrolling, like the sign-out
                // dialogs (round 5): the body embeds the playlist's
                // name, which is user text with no length cap, so a
                // long name at a 2.0x font scale could push Delete /
                // Cancel past the dialog's edge — the same overflow
                // class rounds 2/4 fixed in the sibling dialogs.
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text("\"${target.name}\" and its track list will be removed from this device.")
                }
            },
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

    if (showClearDownloads) {
        AlertDialog(
            onDismissRequest = { showClearDownloads = false },
            title = { Text("Clear all downloads?") },
            text = {
                Text(
                    "Every downloaded track will be removed from this " +
                        "device. Tracks can be downloaded again from " +
                        "their menus."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDownloads = false
                        container.downloadManager.deleteAll()
                    },
                ) { Text("Clear all") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDownloads = false }) { Text("Cancel") }
            },
        )
    }
}

/** Plain-language status line for one Downloads row. */
private fun downloadStatusText(record: DownloadRecord, fileLength: Long?): String =
    when (record.state) {
        DownloadState.QUEUED -> "Waiting to download"
        DownloadState.DOWNLOADING -> {
            val percent = downloadProgressPercent(record)
            if (percent != null) {
                "Downloading… $percent%"
            } else {
                "Downloading…"
            }
        }
        DownloadState.DONE ->
            "Downloaded • ${formatDownloadBytes(fileLength ?: record.totalBytes ?: 0L)}"
        DownloadState.FAILED ->
            "Download failed: ${record.failureReason ?: "something went wrong"}"
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
                // IME Done confirms, exactly as the button does
                // (bug sweep round 53): this field shipped with
                // no keyboard options or actions at all, so the
                // keyboard's Done key neither confirmed the name
                // nor dismissed — the round-52 search defect's
                // sibling on the app's only other text input.
                // Confirming dismisses the dialog (the callers
                // clear their dialog state in onConfirm), which
                // takes the keyboard with it; a blank name stays
                // unconfirmable, matching the button's enabled
                // state and the callers' own blank guard.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (name.isNotBlank()) onConfirm(name) },
                ),
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
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
) {
    val playlists by container.localPlaylistStore.playlists.collectAsState()
    val scope = rememberCoroutineScope()
    val trackActions = dev.cyberdogs.offleash.ui.components.rememberTrackActions(
        container, onAlbumClick, onArtistClick,
    )
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
        // The key carries the index as well as the id (round 4):
        // the store lets the same track be added to a playlist
        // twice, and a LazyColumn keyed on videoId alone throws on
        // the duplicate key and crashes the screen the moment such
        // a playlist is opened.
        itemsIndexed(
            playlist.tracks,
            key = { index, track -> "$index:${track.videoId}" },
        ) { index, track ->
            TrackRow(
                track = track,
                onClick = { container.queueController.playTracks(playlist.tracks, index) },
                onPlayNext = { container.queueController.playNext(it) },
                onAddToQueue = { container.queueController.addToQueue(it) },
                playlists = playlists,
                onAddToPlaylist = { target, picked ->
                    scope.launch { container.localPlaylistStore.addTrack(target.id, picked) }
                },
                trackActions = trackActions,
                trailing = {
                    Row {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    container.localPlaylistStore.moveTrack(
                                        playlist.id,
                                        index,
                                        index - 1,
                                        track,
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
                                        track,
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
                                    // Row-exact removal (round 5): the
                                    // same track may appear twice in a
                                    // playlist, and remove-by-id deleted
                                    // every copy when one row was deleted.
                                    // The row's full Track value rides
                                    // along as a stale-tap guard (round
                                    // 6, full-value form round 8): if
                                    // the list shifted between render
                                    // and this coroutine running, the
                                    // index alone would delete a track
                                    // the user never tapped Delete on —
                                    // and a videoId alone cannot tell
                                    // duplicate copies apart.
                                    container.localPlaylistStore.removeTrackAt(
                                        playlist.id,
                                        index,
                                        track,
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
