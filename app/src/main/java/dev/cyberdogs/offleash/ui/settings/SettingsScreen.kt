package dev.cyberdogs.offleash.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.cyberdogs.offleash.AppContainer
import dev.cyberdogs.offleash.core.playback.QualityPreference
import dev.cyberdogs.offleash.data.DownloadQualityPreference
import kotlinx.coroutines.launch

/** The short label shown for one quality choice. */
fun qualityLabel(quality: QualityPreference): String = when (quality) {
    QualityPreference.LOW -> "Low"
    QualityPreference.BALANCED -> "Balanced"
    QualityPreference.HIGH -> "High"
}

/** The plain-language caption under one quality choice. */
fun qualityCaption(quality: QualityPreference): String = when (quality) {
    QualityPreference.LOW -> "Smallest streams — about up to 80 kbps. Best for mobile data."
    QualityPreference.BALANCED -> "Up to about 160 kbps."
    QualityPreference.HIGH -> "Best quality YouTube offers for the track."
}

/** The short label shown for one download-quality choice (Lane H). */
fun downloadQualityLabel(quality: DownloadQualityPreference): String = when (quality) {
    DownloadQualityPreference.FOLLOW_STREAM -> "Follow stream quality"
    DownloadQualityPreference.LOW -> "Low"
    DownloadQualityPreference.BALANCED -> "Balanced"
    DownloadQualityPreference.HIGH -> "High"
}

/** The plain-language caption under one download-quality choice (Lane H). */
fun downloadQualityCaption(quality: DownloadQualityPreference): String = when (quality) {
    DownloadQualityPreference.FOLLOW_STREAM ->
        "Downloads use the same quality as the stream quality above."
    DownloadQualityPreference.LOW ->
        "Smallest downloads — about up to 80 kbps. Best for mobile data."
    DownloadQualityPreference.BALANCED -> "Up to about 160 kbps."
    DownloadQualityPreference.HIGH -> "Best quality YouTube offers for the track."
}

/**
 * Settings: every control here is functional and persists immediately.
 * The stream-quality rows write to
 * [dev.cyberdogs.offleash.data.AppPreferences] (Lane E, unchanged);
 * the Downloads and Playback controls write the Lane F-owned parity
 * keys (`download_quality`, `auto_download_liked`,
 * `audio_version_in_radio`) through
 * [dev.cyberdogs.offleash.data.ParityPreferences] over the same
 * preferences file. The account block mirrors About's, driven by the
 * presented account state (the container's accountUiState):
 * sign-in routes to the existing sign-in screen, sign-out goes
 * through the same revoke-and-wipe path (`signOutOfAccount()`)
 * About uses — nothing here reimplements it.
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onSignInClick: () -> Unit = {},
) {
    val streamQuality by container.appPreferences.streamQualityFlow.collectAsState()
    val downloadQuality by container.parityPreferences.downloadQualityFlow.collectAsState()
    val autoDownloadLiked by container.parityPreferences.autoDownloadLikedFlow.collectAsState()
    val audioVersionInRadio by container.parityPreferences.audioVersionInRadioFlow.collectAsState()
    val sessionState by container.accountUiState.collectAsState()
    val scope = rememberCoroutineScope()
    var showSignOutConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Stream quality",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Applies to the next track you play.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        QualityPreference.entries.forEach { quality ->
            QualityRow(
                quality = quality,
                selected = quality == streamQuality,
                onSelect = { container.appPreferences.setStreamQuality(quality) },
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Downloads",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "The quality downloads are saved at, and whether liked songs download on their own.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        DownloadQualityPreference.entries.forEach { quality ->
            DownloadQualityRow(
                quality = quality,
                selected = quality == downloadQuality,
                onSelect = { container.parityPreferences.setDownloadQuality(quality) },
            )
        }
        Spacer(Modifier.height(8.dp))
        ToggleRow(
            title = "Auto-download Liked songs",
            caption = "New songs you like are downloaded automatically.",
            checked = autoDownloadLiked,
            onCheckedChange = { container.parityPreferences.setAutoDownloadLiked(it) },
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Playback",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        ToggleRow(
            title = "Play audio version in radio",
            caption = "In radio, play the audio version of a song instead of its music video.",
            checked = audioVersionInRadio,
            onCheckedChange = { container.parityPreferences.setAudioVersionInRadio(it) },
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Account",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Manage the YouTube account this device is signed in with.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (!sessionState.signedIn) {
            Text(
                text = "You're signed out. Browsing and playback stay fully anonymous.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSignInClick) {
                Text("Sign in")
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sessionState.avatarUrl != null) {
                    AsyncImage(
                        model = sessionState.avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column {
                    Text(
                        text = sessionState.identityName
                            ?: sessionState.identityEmail
                            ?: "Signed in",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (sessionState.identityName != null &&
                        sessionState.identityEmail != null
                    ) {
                        Text(
                            text = sessionState.identityEmail ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            when (sessionState.health.name) {
                "HEALTHY" -> Text(
                    text = "Session: healthy",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                "NEEDS_SIGN_IN" -> {
                    Text(
                        text = "Session: sign in again",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onSignInClick) {
                        Text("Sign in again")
                    }
                }
                else -> Text(
                    text = "Session: checking…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showSignOutConfirm = true }) {
                Text("Sign out")
            }
        }
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = { Text("Sign out?") },
            text = {
                Text(
                    "Your sign-in will be removed from this device: " +
                        "Google is told to revoke it, and the saved " +
                        "sign-in is wiped. Your local playlists and " +
                        "history stay — they were never tied to your account.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutConfirm = false
                        scope.launch { container.signOutOfAccount() }
                    },
                ) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

/** One selectable stream-quality row: radio, label, and caption. */
@Composable
private fun QualityRow(
    quality: QualityPreference,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = qualityLabel(quality),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
            )
            Text(
                text = qualityCaption(quality),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One selectable download-quality row: radio, label, and caption. */
@Composable
private fun DownloadQualityRow(
    quality: DownloadQualityPreference,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = downloadQualityLabel(quality),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
            )
            Text(
                text = downloadQualityCaption(quality),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One persisted toggle row: title + caption on the left, switch on the right. */
@Composable
private fun ToggleRow(
    title: String,
    caption: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
