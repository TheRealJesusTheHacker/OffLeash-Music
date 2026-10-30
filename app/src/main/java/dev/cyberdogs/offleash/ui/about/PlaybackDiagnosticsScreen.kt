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

    // Test playback runs its stages in the scope below; while one
    // run is in flight the button is disabled — a second tap used to
    // launch a concurrent run whose stages interleaved into the same
    // log and double-handed the probe track to the player.
    var testRunning by remember { mutableStateOf(false) }

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
        // UNKNOWN also means "the last check couldn't reach Google"
        // and persists until the next one — a present-tense checking
        // label claimed an activity that had already ended
        // (chaos sweep round 2).
        else -> "Not confirmed"
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
                "attempt, stage by stage. If a track " +
                "fails, open this screen and send a screenshot of it — " +
                "that is what turns a failure into a fixable bug. " +
                "One thing here is personal: the account block below " +
                "shows the name or email of the signed-in account, " +
                "and Share diagnostics sends that line too. Nothing else " +
                "is — no sign-in token or password is ever shown, and " +
                "the log names the stream server's host, never an " +
                "address of yours.",
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
                testRunning = true
                scope.launch {
                    try {
                        runTestPlayback(container, appContext)
                    } finally {
                        testRunning = false
                    }
                }
            },
            enabled = !testRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (testRunning) "Testing…" else "Test playback")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                // The screen's own instructions end in "send a
                // screenshot" — this hands the same lines to any
                // share target directly, so sending them never
                // depended on taking one. Only the rendered
                // diagnostics text is shared: the same lines this
                // screen shows, nothing else.
                val shareText = buildString {
                    appendLine("Account: $accountIdentityLine")
                    appendLine("Session: $accountHealthLine")
                    appendLine("PlaybackService: $sessionServiceLine")
                    appendLine("Foreground: $sessionForegroundLine")
                    appendLine("Battery: $sessionBatteryLine")
                    appendLine()
                    append(
                        if (lines.isEmpty()) {
                            diagnostics.renderText()
                        } else {
                            lines.joinToString("\n")
                        },
                    )
                }
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "OffLeash playback diagnostics")
                }
                context.startActivity(
                    android.content.Intent.createChooser(send, "Share diagnostics").apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Share diagnostics")
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
        // Class name only (bug sweep round 6): this log is SHARED
        // by the Share diagnostics button, and a transport
        // exception's message can embed the signed stream URL it
        // failed to fetch — the one raw-message sink the rest of
        // the diagnostics layer had already scrubbed.
        diagnostics.log("test-fetch", "failed (${e.javaClass.simpleName})")
    }
    // (c) Hand the track to the player.
    diagnostics.log("test", "handing the probe track to the player")
    container.queueController.playTestTrack(PROBE_TRACK)
}
