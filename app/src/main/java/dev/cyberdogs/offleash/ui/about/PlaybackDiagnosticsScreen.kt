package dev.cyberdogs.offleash.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.cyberdogs.offleash.AppContainer
import dev.cyberdogs.offleash.BuildConfig
import dev.cyberdogs.offleash.core.model.Track
import dev.cyberdogs.offleash.playback.batteryStatusText
import dev.cyberdogs.offleash.playback.foregroundStatusText
import dev.cyberdogs.offleash.playback.serviceRunningText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/** The fixed probe track used by Test playback. */
private val PROBE_TRACK = Track(
    videoId = "lY5V4hSLWY8",
    title = "Risk It All",
    artists = listOf("Bruno Mars"),
    artistBrowseId = null,
    album = null,
    albumBrowseId = null,
    durationSeconds = null,
    thumbnailUrl = null,
)

@Composable
fun PlaybackDiagnosticsScreen(container: AppContainer) {
    val diagnostics = container.playbackDiagnostics
    val lines by diagnostics.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    val context = LocalContext.current

    // Session & battery block: computed live on render, refreshed on resume.
    var refreshTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val sessionServiceLine = remember(refreshTick, lines) { serviceRunningText(diagnostics) }
    val sessionForegroundLine = remember(refreshTick, lines) { foregroundStatusText(context) }
    val sessionBatteryLine = remember(refreshTick, lines) { batteryStatusText(context) }

    // Account & session block: identity display name + health label
    // only — never cookie values or anything derived from them.
    val sessionState by container.accountUiState.collectAsState()
    val accountIdentityLine = if (sessionState.signedIn) {
        sessionState.identityName ?: sessionState.identityEmail ?: "Signed in"
    } else {
        "Signed out"
    }
    val accountHealthLine = when (sessionState.health.name) {
        "HEALTHY" -> "Healthy"
        "NEEDS_SIGN_IN" -> "Sign in again"
        else -> "Checking…"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = "Playback diagnostics",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (BuildConfig.EDITION_LABEL.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = BuildConfig.EDITION_LABEL,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "This screen shows what happened on the last playback " +
                "attempt, stage by stage, in plain language. If a track " +
                "fails, open this screen and send a screenshot of it — " +
                "that is what turns a failure into a fixable bug. " +
                "No addresses or personal data are shown here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Account & session",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Account: $accountIdentityLine\n" +
                "Session: $accountHealthLine",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Session & battery",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "PlaybackService: $sessionServiceLine\n" +
                "Foreground: $sessionForegroundLine\n" +
                "Battery: $sessionBatteryLine",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                scope.launch {
                    runTestPlayback(container, appContext)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Test playback")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Test playback resolves the probe track “Risk It All” " +
                "through the real client, fetches its first bytes, and " +
                "then hands it to the player — each stage is logged below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = if (lines.isEmpty()) {
                diagnostics.renderText()
            } else {
                lines.joinToString("\n")
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private suspend fun runTestPlayback(container: AppContainer, appContext: android.content.Context) {
    val diagnostics = container.playbackDiagnostics
    diagnostics.startAttempt("test playback — ${PROBE_TRACK.videoId} — ${PROBE_TRACK.title}")
    diagnostics.recordNetworkFrom(appContext)
    // (a) Resolve through the real client.
    val stream = try {
        val resolved = withContext(Dispatchers.IO) {
            container.client.streamInfo(PROBE_TRACK.videoId)
        }
        diagnostics.recordResolverOutcome(
            contextClientName = "IOS",
            contextClientVersion = "20.10.4",
            exceptionClass = null,
        )
        diagnostics.recordChosenFormat(resolved.mimeType, resolved.bitrate, resolved.url)
        resolved
    } catch (e: Exception) {
        diagnostics.recordResolverOutcome(
            contextClientName = "IOS",
            contextClientVersion = "20.10.4",
            exceptionClass = e.javaClass.simpleName,
        )
        diagnostics.log("test", "resolve failed — test stops here")
        return
    }
    // (b) Fetch the first bytes through the shared OkHttp client. The
    // scoped RangeInterceptor normalizes the range to a bounded window
    // and records the status into diagnostics itself.
    try {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(stream.url).get().build()
            container.okHttpClient.newCall(request).execute().use { response ->
                val source = response.body?.source()
                source?.request(2048)
                val bytesRead = source?.buffer?.size ?: 0L
                diagnostics.log(
                    "test-fetch",
                    "HTTP ${response.code} bytes=$bytesRead",
                )
            }
        }
    } catch (e: Exception) {
        diagnostics.log("test-fetch", "failed (${e.javaClass.simpleName}: ${e.message})")
    }
    // (c) Hand the track to the player.
    diagnostics.log("test", "handing the probe track to the player")
    container.queueController.playTestTrack(PROBE_TRACK)
}
