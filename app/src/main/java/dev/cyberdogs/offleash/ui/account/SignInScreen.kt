package dev.cyberdogs.offleash.ui.account

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.cyberdogs.offleash.AppContainer
import dev.cyberdogs.offleash.data.GoogleSignInPhase

/**
 * Sign in with Google (0.8.8): the native browser flow.
 *
 * One tap on "Continue with Google" opens Google's own sign-in
 * page in the browser (a Chrome Custom Tab where available): the
 * owner picks the Google account already on the phone and
 * approves there. Google sends them straight back into the app,
 * which exchanges the returned code for a sign-in token — stored
 * encrypted on this device and used only to ask YouTube Music
 * for the owner's home feed, playlists, and liked songs.
 *
 * No password is ever typed into this app, there is no code to
 * copy, no console setup, and nothing to paste. The device-code
 * flow this screen used to render (0.7.0–0.8.7) and the own-key
 * wizard card that stood below it are gone from the sign-in path
 * entirely.
 *
 * The screen is a pure rendering of the Google session manager's
 * phase + sign-in state: the manager owns the in-flight attempt
 * (it is persisted, sealed, the moment it is built) and the
 * token custody, so leaving this screen — or the app being
 * killed while the owner is in the browser — loses nothing:
 * coming back shows the same attempt, still waiting, exactly
 * where it stands.
 */
@Composable
fun SignInScreen(
    container: AppContainer,
    onSignedIn: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val manager = container.googleSessionManager
    val phase by manager.phase.collectAsState()
    val sessionState by manager.uiState.collectAsState()
    val launch by manager.authorizationLaunch.collectAsState()
    val attemptDurable by manager.pendingAttemptDurability.collectAsState()

    // The last crash the app recorded, if any: after a crash this
    // screen is where the owner lands to retry, so this is where
    // the trace surfaces — local only, dismissible, gone once
    // cleared. (Carried over from the 0.6.x screen unchanged.)
    var savedCrash by remember {
        mutableStateOf(
            dev.cyberdogs.offleash.data.CrashReporter.read(
                dev.cyberdogs.offleash.data.CrashReporter.fileIn(context)
            )
        )
    }

    LaunchedEffect(sessionState.signedIn) {
        if (sessionState.signedIn) onSignedIn()
    }

    // Opens Google's page for [url]: a Custom Tab where the
    // browser offers one (the account picker and approval stay in
    // Google's own surface, and the return lands back here), a
    // plain browser view everywhere else. Either way the flow is
    // the same one tap from the owner's side.
    fun openGooglePage(url: String) {
        val uri = Uri.parse(url)
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(context, uri)
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e2: ActivityNotFoundException) {
                // No browser at all: the waiting card below stays
                // on screen with its honest text; diagnostics note
                // the launch failure through the manager's trail
                // the next time the flow is examined.
            }
        }
    }

    // Starts a fresh attempt and opens its page in the same tap:
    // the attempt is built synchronously, so its launch is
    // already published when startSignIn returns.
    fun startAndOpen() {
        manager.startSignIn()
        manager.authorizationLaunch.value?.let { openGooglePage(it.url) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text(
            text = "Sign in with Google",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Sign in with the Google account on this phone " +
                "to load your home feed, your playlists, and your " +
                "liked songs.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        var showSafetyDetails by remember { mutableStateOf(false) }
        TextButton(
            onClick = { showSafetyDetails = !showSafetyDetails },
        ) {
            Text(
                if (showSafetyDetails) {
                    "Hide how this keeps your password safe"
                } else {
                    "How this keeps your password safe"
                },
            )
        }
        if (showSafetyDetails) {
            Text(
                text = "Tapping Continue opens Google's own sign-in " +
                    "page in your browser. You pick the account " +
                    "already on this phone and approve there — " +
                    "your password is typed only on Google's page, " +
                    "never in this app, and there is no code to " +
                    "copy and nothing to set up.\n\n" +
                    "Google then sends you straight back here and " +
                    "hands the app a sign-in token. The token is " +
                    "stored encrypted on this device, and OffLeash " +
                    "uses it only to ask YouTube Music for your " +
                    "home feed, your playlists, and your liked " +
                    "songs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        savedCrash?.let { trace ->
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "The app stopped during the last sign-in attempt. " +
                            "Here's what it recorded:",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = trace,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            dev.cyberdogs.offleash.data.CrashReporter.clear(
                                dev.cyberdogs.offleash.data.CrashReporter.fileIn(context)
                            )
                            savedCrash = null
                        },
                    ) { Text("Clear") }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        when (val current = phase) {
            GoogleSignInPhase.Idle -> {
                // The standing end reason, when there is one: a
                // session the client-change gate ended lands here
                // with its honest explanation — shown until the
                // owner signs in again, never as an error loop.
                val endReason = sessionState.lastEndReason
                if (endReason != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor =
                                MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = endReason,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (container.googleAuth.isConfigured) {
                    Button(
                        onClick = { startAndOpen() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Continue with Google") }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "You'll pick your account and approve " +
                            "on Google's own page, then come straight " +
                            "back here signed in. No code to type, " +
                            "nothing to set up.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Google sign-in isn't set up in this " +
                            "build of OffLeash yet, so there's " +
                            "nothing to sign in with. Everything " +
                            "else in the app works as normal.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            GoogleSignInPhase.Issuing -> {
                Column {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Opening Google…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            is GoogleSignInPhase.AwaitingReturn -> {
                Text(
                    text = "Finish signing in on Google's page",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Google's page should be open in your " +
                        "browser. Pick your account and approve — " +
                        "you'll come straight back here signed in.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!attemptDurable) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Keep the app open until you're " +
                            "back — this attempt couldn't be saved, " +
                            "so it won't survive the app closing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        launch?.let { openGooglePage(it.url) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continue signing in") }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { manager.cancelSignIn() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel") }
            }

            is GoogleSignInPhase.Failed -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = current.reason,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val signInLines = container.signInDiagnostics.lines()
                        if (signInLines.isNotEmpty()) {
                            var showDetails by remember {
                                mutableStateOf(false)
                            }
                            TextButton(
                                onClick = { showDetails = !showDetails },
                            ) {
                                Text(
                                    if (showDetails) {
                                        "Hide details for support"
                                    } else {
                                        "Details for support"
                                    },
                                )
                            }
                            if (showDetails) {
                                Text(
                                    text = signInLines.joinToString("\n"),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color =
                                        MaterialTheme.colorScheme
                                            .onSurfaceVariant,
                                )
                            }
                        }
                        if (container.googleAuth.isConfigured) {
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { startAndOpen() },
                            ) { Text("Try again") }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
