package dev.cyberdogs.offleash.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.cyberdogs.offleash.R

private fun rememberVersionName(context: android.content.Context): String {
    return try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }
}

/** Third-party libraries this build stands on — every one Apache-2.0. */
private val THIRD_PARTY_CREDITS: List<Pair<String, String>> = listOf(
    "Kotlin standard library" to "Apache License 2.0",
    "Jetpack Compose (UI, Material 3, Navigation, Lifecycle)" to "Apache License 2.0",
    "AndroidX Media3 (ExoPlayer, session, datasource)" to "Apache License 2.0",
    "OkHttp" to "Apache License 2.0",
    "kotlinx-serialization" to "Apache License 2.0",
    "kotlinx-coroutines" to "Apache License 2.0",
    "Coil" to "Apache License 2.0",
)

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val versionName = rememberVersionName(context)
    var showLicense by remember { mutableStateOf(false) }
    val licenseText = remember(showLicense) {
        if (!showLicense) {
            ""
        } else {
            try {
                context.resources.openRawResource(R.raw.gpl3).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                "The license text could not be loaded."
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(id = R.drawable.cyberdogs_logo),
            contentDescription = "Cyberdogs logo",
            modifier = Modifier.size(160.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "OffLeash Music",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Version $versionName",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Breed don't fall.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "OffLeash Music is a Cyberdogs app, built by Sentinel Safeguard. " +
                "It plays music from YouTube Music's own servers through the same " +
                "unofficial internal API the YouTube Music web app uses. The app " +
                "hosts nothing, stores nothing on any server, and sells nothing. " +
                "There is no account and no sign-in: your playlists and history " +
                "live only on this device.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Sign-in is planned for a later phase. It arrives only after its " +
                "own dedicated security pass — until then, nothing in this app " +
                "handles your Google account or stores any credential.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "If you have YouTube Premium, keep it — Premium pays the artists " +
                "and this app is a different way in, not a replacement for supporting them.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "License: OffLeash Music is free software under the GNU General " +
                "Public License, version 3. You may use, study, share and change it " +
                "under the terms of that license.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { showLicense = !showLicense }) {
            Text(if (showLicense) "Hide the full license" else "Read the full license")
        }
        if (showLicense) {
            Text(
                text = licenseText,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
        Text(
            text = "Built with",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        THIRD_PARTY_CREDITS.forEach { (name, license) ->
            Text(
                text = "$name — $license",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "The full dependency ledger, with exact versions, lives in " +
                "docs/LICENSES.md in the source repository.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Built in the spirit of the open YouTube Music client category — " +
                "thanks to the projects that proved a clean, honest client is possible. " +
                "OffLeash is an independent, clean-room implementation: all code here " +
                "is our own.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
