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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.cyberdogs.offleash.AppContainer
import dev.cyberdogs.offleash.core.playback.QualityPreference
import dev.cyberdogs.offleash.data.DownloadQuality
import dev.cyberdogs.offleash.data.DownloadQualityPreference

/**
 * The [DownloadQuality] twin of a Settings [DownloadQualityPreference]:
 * the two enums are parallel by design (Lane F owns one, Lane H the
 * other) and share their stored names, so the mapping is the name.
 * Settings writes BOTH accessors on every change (see the screen's
 * KDoc) — this is the conversion the AppPreferences write needs.
 */
fun downloadQualityFor(quality: DownloadQualityPreference): DownloadQuality =
    DownloadQuality.valueOf(quality.name)

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
 * Settings: every control here persists immediately, and every
 * control takes effect as its caption describes — the one partial
 * exception is the radio audio-version toggle, whose caption says
 * plainly that it is saved but not yet in effect (the radio builder
 * documents why: the API gives no audio-vs-video distinction to
 * act on; see RadioQueue.kt).
 * The stream-quality rows write to
 * [dev.cyberdogs.offleash.data.AppPreferences] (Lane E, unchanged);
 * the Downloads and Playback controls write the Lane F-owned parity
 * keys (`download_quality`, `auto_download_liked`,
 * `audio_version_in_radio`) through
 * [dev.cyberdogs.offleash.data.ParityPreferences] over the same
 * preferences file — AND through [dev.cyberdogs.offleash.data.AppPreferences],
 * whose accessors are what the download engine, the auto-download
 * worker, and the queue controller actually read. The two classes
 * hold separate in-memory caches over one file; writing only one
 * left the other's readers (and flow collectors, e.g. the Library's
 * auto-download trigger) serving the old value until a restart —
 * a control that persisted but did not take effect. Both classes'
 * getters also read through to the persisted value, so the dual
 * write is belt and braces, not the only line of defence.
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
) {
    val streamQuality by container.appPreferences.streamQualityFlow.collectAsState()
    val downloadQuality by container.parityPreferences.downloadQualityFlow.collectAsState()
    val autoDownloadLiked by container.parityPreferences.autoDownloadLikedFlow.collectAsState()
    val audioVersionInRadio by container.parityPreferences.audioVersionInRadioFlow.collectAsState()

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
            style = MaterialTheme.typography.titleLarge,
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
            style = MaterialTheme.typography.titleLarge,
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
                onSelect = {
                    container.parityPreferences.setDownloadQuality(quality)
                    container.appPreferences.setDownloadQuality(downloadQualityFor(quality))
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        ToggleRow(
            title = "Auto-download Liked songs",
            caption = "New songs you like are downloaded automatically.",
            checked = autoDownloadLiked,
            onCheckedChange = {
                container.parityPreferences.setAutoDownloadLiked(it)
                container.appPreferences.setAutoDownloadLiked(it)
            },
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Playback",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        ToggleRow(
            title = "Play audio version in radio",
            caption = "Saved for when YouTube's radio answers say which " +
                "tracks are audio versions — they don't today, so this " +
                "choice doesn't change radio yet.",
            checked = audioVersionInRadio,
            onCheckedChange = {
                container.parityPreferences.setAudioVersionInRadio(it)
                container.appPreferences.setAudioVersionInRadio(it)
            },
        )
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
