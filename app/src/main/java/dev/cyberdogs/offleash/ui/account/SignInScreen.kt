package dev.cyberdogs.offleash.ui.account

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.cyberdogs.offleash.AppContainer
import dev.cyberdogs.offleash.data.GoogleSignInPhase

/**
 * Sign in with Google (0.7.0): the device-code flow.
 *
 * The app asks Google for a short code, shows it here, and the
 * owner enters it at `google.com/device` in ANY browser — this
 * phone's own browser is the expected one — and approves with
 * their Google account. Google then hands the app a token, which
 * is stored encrypted on this device and used only to ask YouTube
 * Music for the owner's home feed, playlists, and liked songs.
 *
 * No password is ever typed into this app, there is no WebView
 * sign-in page, and no browser cookie is captured or kept — the
 * 0.6.x WebView/paste sign-in that used to live in this file is
 * disconnected from the flow entirely (see docs/AUDITS.md for the
 * exact disconnection inventory; its supporting code remains in
 * the repo for one release as a safety net, unreachable from any
 * navigation).
 *
 * The screen is a pure rendering of the Google session manager's
 * phase + sign-in state: the manager owns the issued grant (it is
 * persisted, sealed, the moment Google issues it) and the poll
 * loop, so leaving this screen — or the app being killed while
 * the owner is in the browser — loses nothing: coming back shows
 * the same code, still being checked, exactly where it stands.
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

    // Ticking clock for the waiting screen's expiry copy (round 3
    // sweep): the minutes-left text below is computed from the
    // grant, but without a tick it was computed once at first
    // composition and then froze — a code with minutes left could
    // go on reading its opening number long after it stopped being
    // true. Thirty seconds is plenty of grain for a
    // minute-granularity sentence.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(phase) {
        while (true) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000L)
        }
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
            text = "Sign-in works the way it does on a TV. Google gives this " +
                "app a short code; you enter it on Google's page, in any " +
                "browser, and approve with your Google account. Google then " +
                "hands the app a sign-in token.\n\n" +
                "Your password is typed only on Google's page — never in " +
                "this app — and no browser cookies are used. The token is " +
                "stored encrypted on this device, and OffLeash uses it " +
                "only to ask YouTube Music for your home feed, your " +
                "playlists, and your liked songs.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

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
                Button(
                    onClick = { manager.startSignIn() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sign in with Google") }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "You'll get a code to enter on Google's page. " +
                        "This takes about a minute.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is GoogleSignInPhase.AwaitingApproval -> {
                Text(
                    text = "Your code",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = current.userCode,
                    style = MaterialTheme.typography.headlineLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                // Copy affordance (bug sweep round 13): the code
                // was display-only — a plain Text is not selectable
                // in Compose, and there was no clipboard path
                // anywhere in the app — so on the expected path
                // (this phone's own browser) the owner had to
                // memorise the code or app-switch back and forth
                // to read it in pieces. One tap now puts the exact
                // code on the clipboard to paste into Google's
                // page; on another device the code is still read
                // and typed as before.
                val clipboardManager = LocalClipboardManager.current
                var codeCopied by remember(current.userCode) {
                    mutableStateOf(false)
                }
                OutlinedButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(current.userCode))
                        codeCopied = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (codeCopied) {
                            "Copied — paste it on Google's page"
                        } else {
                            "Copy code"
                        },
                    )
                }
                Spacer(Modifier.height(16.dp))
                // The remaining life is computed from the grant
                // itself (0.7.2 sweep): a code RESUMED after the app
                // was restarted may have only a few minutes left,
                // and telling the owner "about 30 minutes" for it
                // would be a lie of exactly the kind the expired
                // ending then has to explain.
                // (Perfection pass: the last partial minute reads
                // "less than a minute" — the coerceAtLeast(1) this
                // replaces sold a code with twenty seconds left as
                // "about 1 minute", a time the grant doesn't give.)
                val remainingMs = (current.expiresAtEpochMs - nowMs)
                    .coerceAtLeast(0L)
                val expiryText = when {
                    remainingMs < 60_000L -> "less than a minute."
                    remainingMs < 120_000L -> "about 1 minute."
                    else -> "about ${remainingMs / 60_000L} minutes."
                }
                Text(
                    text = "1. Open google.com/device in any browser — " +
                        "this phone's browser works.\n" +
                        "2. Enter the code above.\n" +
                        "3. Approve with your Google account.\n\n" +
                        "This screen finishes on its own once you approve. " +
                        "The code expires in $expiryText",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.google.com/device"),
                        )
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open google.com/device") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { manager.checkNow() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("I've approved — check now") }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "Waiting for your approval — the app is " +
                            "checking with Google on its own…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "What happened",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = signInLines.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { manager.startSignIn() },
                        ) { Text("Try again") }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
