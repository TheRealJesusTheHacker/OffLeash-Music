package dev.cyberdogs.offleash.ui.account

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.cyberdogs.offleash.AppContainer
import dev.cyberdogs.offleash.data.SignInAttempt
import dev.cyberdogs.offleash.data.wipeWebViewData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Google's own sign-in page, in the music-service flow (the URL the whole category uses). */
private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&uilel=3" +
        "&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2F" +
        "&flowName=GlifWebSignIn&flowEntry=ServiceLogin"

/** Sign-in is done when the flow lands exactly here. */
private const val SIGNED_IN_URL = "https://music.youtube.com/"

private const val MUSIC_URL = "https://music.youtube.com"

/**
 * The only hosts the sign-in view may ever visit: Google's sign-in
 * family and YouTube's. Subdomains OF a listed host are allowed;
 * lookalike hosts that merely end in similar text are not in the
 * set and are stopped.
 */
private val ALLOWED_SIGN_IN_HOSTS = setOf(
    "accounts.google.com",
    "www.google.com",
    "google.com",
    "accounts.youtube.com",
    "www.youtube.com",
    "youtube.com",
    "music.youtube.com",
    "myaccount.google.com",
)

internal fun isAllowedSignInHost(host: String?): Boolean {
    val normalized = host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
    if (normalized in ALLOWED_SIGN_IN_HOSTS) return true
    return ALLOWED_SIGN_IN_HOSTS.any { allowed -> normalized.endsWith(".$allowed") }
}

private enum class SignInMode { WEB, PASTE }

/**
 * Sign in with Google, for YouTube Music account features.
 *
 * The sign-in itself happens entirely on Google's page inside this
 * view — this app never sees or handles the password. When the flow
 * lands on YouTube Music, only the session cookie for YouTube is
 * read out of the view, handed to the session manager (which checks
 * it with YouTube before storing anything, encrypted), and the
 * view's own cookie jar is wiped straight away either way.
 */
@Composable
fun SignInScreen(
    container: AppContainer,
    onSignedIn: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = container.sessionManager
    // The WebView below is created once and outlives recompositions;
    // it must always reach the CURRENT success callback.
    val latestOnSignedIn by rememberUpdatedState(onSignedIn)

    var mode by remember { mutableStateOf(SignInMode.WEB) }
    var validating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var blockedNote by remember { mutableStateOf<String?>(null) }
    var pasteText by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // The last crash the app recorded, if any (2026-10-27 crash
    // hunt): after a crash this screen is where the owner lands to
    // retry, so this is where the trace surfaces — local only,
    // dismissible, and gone once cleared.
    var savedCrash by remember {
        mutableStateOf(
            dev.cyberdogs.offleash.data.CrashReporter.read(
                dev.cyberdogs.offleash.data.CrashReporter.fileIn(context)
            )
        )
    }

    // One pipeline for both capture paths: ask the manager, then —
    // whatever the answer — wipe the WebView's jar, because the
    // session now lives (if anywhere) in the encrypted store only.
    // The cookie source is a PROVIDER, not a snapshot: confirmation
    // re-reads it across its settling window, so cookies Google is
    // still minting in the seconds after landing are picked up
    // instead of bouncing a real login on a half-landed capture
    // (2026-10-24 sign-in hunt).
    fun attemptSignIn(cookieProvider: suspend () -> String) {
        if (validating) return
        validating = true
        errorMessage = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // Last-resort guard (2026-10-27 crash hunt): the
                // manager's confirmation is total by contract, but
                // this launch has no exception handler — anything
                // that ever escaped from here crashed the whole app
                // (0.6.3's sign-in crash). A surprise becomes the
                // failure card, never a dead process.
                try {
                    manager.completeSignIn(cookieProvider)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    SignInAttempt.Failed(
                        "Something went wrong while confirming the sign-in. " +
                            "Nothing was saved. Try signing in again."
                    )
                }
            }
            wipeWebViewData(context)
            when (result) {
                is SignInAttempt.Success -> latestOnSignedIn()
                is SignInAttempt.Failed -> {
                    validating = false
                    errorMessage = result.reason
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text(
            text = "Sign in with Google",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Sign-in happens on Google's own page, inside this view. " +
                "This app never sees your password — Google handles that part alone. " +
                "When you finish, only the session cookie is kept, encrypted on " +
                "this device, so the app can show your library and playlists. " +
                "Nothing about your account is sent anywhere else.",
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

        blockedNote?.let { note ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        errorMessage?.let { message ->
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = message,
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
                        onClick = {
                            errorMessage = null
                            validating = false
                            if (mode == SignInMode.WEB) {
                                // Start the flow over from Google's page.
                                CookieManager.getInstance().removeAllCookies(null)
                                CookieManager.getInstance().flush()
                                webView?.loadUrl(LOGIN_URL)
                            }
                        },
                    ) { Text("Try again") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (mode == SignInMode.WEB) {
            if (validating) {
                // The WebView stays composed below while confirmation
                // runs: the page keeps settling — and Google keeps
                // minting the session's cookies — while the manager
                // re-reads the jar across its window. The pre-hunt
                // shape replaced the view with this spinner, which
                // destroyed the view mid-settle (2026-10-24 hunt).
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
                        text = "Checking your sign-in with YouTube — this can take " +
                            "a moment while YouTube finishes setting up your session…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            AndroidView(
                factory = { viewContext ->
                    WebView(viewContext).apply {
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.setSupportMultipleWindows(false)
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val uri = request?.url ?: return false
                                return enforceNavigationScope(uri) { note ->
                                    blockedNote = note
                                }
                            }

                            @Deprecated("Deprecated in Android WebView")
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                url: String?,
                            ): Boolean {
                                val uri = url?.let { Uri.parse(it) } ?: return false
                                return enforceNavigationScope(uri) { note ->
                                    blockedNote = note
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (url == SIGNED_IN_URL && !validating) {
                                    // Hand over a live cookie source,
                                    // not a one-shot capture: the jar
                                    // keeps filling after this first
                                    // landing, and confirmation re-reads
                                    // it across its window.
                                    attemptSignIn {
                                        CookieManager.getInstance()
                                            .getCookie(MUSIC_URL) ?: ""
                                    }
                                }
                            }
                        }
                        // Every (re)entry starts from an empty jar, so
                        // a leftover half-finished attempt can never
                        // be mistaken for this sign-in.
                        cookieManager.removeAllCookies(null)
                        cookieManager.flush()
                        loadUrl(LOGIN_URL)
                    }
                },
                onRelease = { view ->
                    if (webView === view) webView = null
                    view.destroy()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    errorMessage = null
                    blockedNote = null
                    mode = SignInMode.PASTE
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Paste a cookie instead") }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Text(
                    text = "For when Google's page keeps looping: sign in at " +
                        "music.youtube.com in your browser, copy the cookie for " +
                        "music.youtube.com, and paste it here. It goes through " +
                        "the same check — YouTube confirms the account before " +
                        "anything is stored, encrypted on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    label = { Text("YouTube session cookie") },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { attemptSignIn { pasteText } },
                    enabled = pasteText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use this cookie") }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        errorMessage = null
                        blockedNote = null
                        mode = SignInMode.WEB
                    },
                ) { Text("Back to Google sign-in") }
            }
        }
    }
}

/**
 * The navigation scope, enforced on every navigation the view tries:
 * returns true (navigation blocked) for anything outside the
 * Google/YouTube sign-in hosts, and reports it in plain language.
 */
private fun enforceNavigationScope(uri: Uri, onBlocked: (String) -> Unit): Boolean {
    // In-page schemes with no host (about:blank and friends) are the
    // view's own business; anything with a host must be in scope.
    val host = uri.host ?: return false
    if (isAllowedSignInHost(host)) return false
    onBlocked("Sign-in tried to leave Google's pages, so it was stopped.")
    return true
}
