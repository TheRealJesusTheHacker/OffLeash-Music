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

/**
 * Settings: every row here is functional — the stream-quality rows
 * persist immediately to [dev.cyberdogs.offleash.data.AppPreferences]
 * (and apply to the next resolve with no restart), and the account
 * block mirrors About's, driven by the same SessionManager uiState:
 * sign-in routes to the existing sign-in screen, sign-out goes
 * through the same confirm dialog About uses.
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onSignInClick: () -> Unit = {},
) {
    val streamQuality by container.appPreferences.streamQualityFlow.collectAsState()
    val sessionState by container.sessionManager.uiState.collectAsState()
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
            text = "Account",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
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
                    "Your session cookie will be wiped from this device. " +
                        "Your local playlists and history stay — they were " +
                        "never tied to your account.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutConfirm = false
                        scope.launch { container.sessionManager.signOut() }
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
