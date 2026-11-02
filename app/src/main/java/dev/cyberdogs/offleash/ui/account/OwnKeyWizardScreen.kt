package dev.cyberdogs.offleash.ui.account

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.cyberdogs.offleash.AppContainer
import dev.cyberdogs.offleash.data.OwnKeyStep
import dev.cyberdogs.offleash.data.OwnKeyValidation

/**
 * Set up your own Google key (0.8.6): the guided wizard.
 *
 * Google limits what OffLeash's shared sign-in can reach, so the
 * owner's library may not load; a key of their own — a small, free
 * registration in their own Google account — is what fixes it.
 * This wizard walks a non-technical owner through creating it,
 * one plain-language step per screen: each card says what to do,
 * its button opens the exact Google page, and the paste screen
 * tests the entered key against Google for real before anything
 * is saved. The owner never handles a file, a console walkthrough
 * from chat, or the word "OAuth" unexplained — in this app it's
 * "your own Google key".
 *
 * The screen is a pure rendering of [dev.cyberdogs.offleash.data.OwnKeySetup]'s
 * state: progress and the draft persist sealed on the device, so
 * leaving mid-flow and coming back resumes exactly here.
 */
@Composable
fun OwnKeyWizardScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val setup = container.ownKeySetup
    val state by setup.state.collectAsState()
    var secretVisible by remember { mutableStateOf(false) }
    var removeArmed by remember { mutableStateOf(false) }

    // While the paste step is showing, the window is marked
    // secure (0.8.7 sweep 2): the client secret sits in a
    // visible field there, and FLAG_SECURE keeps it out of
    // screenshots and the recents thumbnail for exactly the
    // step that shows it. The flag is cleared the moment the
    // step changes or the screen leaves, so no other surface
    // loses its screenshots to this step's secret.
    val activity = context as? android.app.Activity
    androidx.compose.runtime.DisposableEffect(state.step) {
        if (state.step == OwnKeyStep.ENTER_KEY) {
            activity?.window?.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        onDispose {
            activity?.window?.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
    }

    fun openPage(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        TextButton(
            onClick = {
                if (state.step == OwnKeyStep.NEED ||
                    state.step == OwnKeyStep.DONE
                ) {
                    onBack()
                } else {
                    setup.back()
                }
            },
        ) { Text("Back") }
        Text(
            text = "Your own Google key",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (state.step != OwnKeyStep.DONE) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Step ${state.step.ordinal + 1} of 6",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))

        when (state.step) {
            OwnKeyStep.NEED -> {
                StepCard(
                    title = "What you'll need",
                    body = "Your own Google key is a small " +
                        "registration you create in your own Google " +
                        "account. It's free, and it's what lets " +
                        "OffLeash load your library — Google limits " +
                        "what the app's shared sign-in can reach.\n\n" +
                        "You'll need:\n" +
                        "• The Google account that owns your YouTube " +
                        "— the one with your playlists\n" +
                        "• About 10 minutes\n" +
                        "• This phone's browser, signed in to that " +
                        "same Google account\n\n" +
                        "You can leave and come back at any point — " +
                        "your place is saved.",
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { setup.advance() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start") }
                if (state.hasOwnKey) {
                    Spacer(Modifier.height(24.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor =
                                MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = "Your own Google key is set " +
                                    "up — sign-in runs under it.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    if (removeArmed) {
                                        setup.removeOwnKey()
                                        removeArmed = false
                                    } else {
                                        removeArmed = true
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (removeArmed) {
                                        "Tap again to remove your key"
                                    } else {
                                        "Remove my key"
                                    },
                                )
                            }
                            if (removeArmed) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "Removing your key signs " +
                                        "you out and returns sign-in " +
                                        "to the app's shared setup.",
                                    style =
                                        MaterialTheme.typography.bodyMedium,
                                    color =
                                        MaterialTheme.colorScheme
                                            .onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            OwnKeyStep.PROJECT -> {
                StepCard(
                    title = "Create your project",
                    body = "A project is just a folder in your " +
                        "Google account that holds your key.\n\n" +
                        "1. Tap the button below — Google's page " +
                        "opens.\n" +
                        "2. Type a name for the project. Anything " +
                        "works — “OffLeash” is a good one.\n" +
                        "3. Tap “Create” and wait a few seconds " +
                        "while Google sets it up.\n" +
                        "4. Come back to this screen and tap Next.",
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { openPage(PROJECT_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open Google's project page") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { setup.advance() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Next") }
            }

            OwnKeyStep.API -> {
                StepCard(
                    title = "Turn on the YouTube API",
                    body = "This tells Google what your key is " +
                        "for.\n\n" +
                        "1. Tap the button below — the YouTube API " +
                        "page opens.\n" +
                        "2. Tap “Enable”.\n" +
                        "3. Come back here and tap Next.\n\n" +
                        "If the page says the API is already " +
                        "enabled, you're done with this step.",
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { openPage(API_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open the YouTube API page") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { setup.advance() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Next") }
            }

            OwnKeyStep.CONSENT -> {
                StepCard(
                    title = "The sign-in screen setting",
                    body = "Google asks how your key's sign-in " +
                        "page should work.\n\n" +
                        "1. Tap the button below.\n" +
                        "2. Choose “External”.\n" +
                        "3. For the app name, type: OffLeash\n" +
                        "4. Fill in your email where Google asks " +
                        "for it, then save.\n" +
                        "5. Tap “Publish app”.\n\n" +
                        "That last one matters: choose Publish. " +
                        "Skip it, and Google signs you out every " +
                        "week.",
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { openPage(CONSENT_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open the sign-in settings page") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { setup.advance() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Next") }
            }

            OwnKeyStep.CREATE_KEY -> {
                StepCard(
                    title = "Create your key",
                    body = "Almost there — this makes the key " +
                        "itself.\n\n" +
                        "1. Tap the button below.\n" +
                        "2. Under “Application type”, choose “TVs " +
                        "and Limited Input devices” — that exact " +
                        "name.\n" +
                        "3. Give it any name you like, and tap " +
                        "“Create”.\n" +
                        "4. Google shows your key: a Client ID " +
                        "and a Client secret. Keep that page open " +
                        "— you'll copy both into the next step.",
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { openPage(CREATE_KEY_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open the key page") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { setup.advance() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Next") }
            }

            OwnKeyStep.ENTER_KEY -> {
                val idError = OwnKeyValidation.clientIdError(
                    state.draftClientId,
                )
                val secretError = OwnKeyValidation.clientSecretError(
                    state.draftClientSecret,
                )
                val formatValid = OwnKeyValidation.isValid(
                    state.draftClientId,
                    state.draftClientSecret,
                )
                StepCard(
                    title = "Paste your key",
                    body = "Copy each line from Google's page " +
                        "and paste it here. As you type, your " +
                        "place is kept — encrypted, on this phone " +
                        "only — so you can leave and come back. " +
                        "Nothing is used or sent anywhere until " +
                        "your key passes its test and you tap " +
                        "“Save my key”.",
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    state.draftClientId,
                    { setup.updateDraft(it, state.draftClientSecret) },
                    label = { Text("Client ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.draftClientId.isNotEmpty() && idError != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = idError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    state.draftClientSecret,
                    { setup.updateDraft(state.draftClientId, it) },
                    label = { Text("Client secret") },
                    singleLine = true,
                    visualTransformation = if (secretVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.draftClientSecret.isNotEmpty() &&
                    secretError != null
                ) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = secretError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row {
                    TextButton(
                        onClick = { secretVisible = !secretVisible },
                    ) {
                        Text(
                            if (secretVisible) {
                                "Hide the secret"
                            } else {
                                "Show the secret"
                            },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { setup.restart() }) {
                        Text("Start over")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { setup.testKey() },
                    enabled = formatValid && !state.testing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Test my key") }
                if (state.testing) {
                    Spacer(Modifier.height(12.dp))
                    Row {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        Text(
                            text = "Testing your key with Google…",
                            style = MaterialTheme.typography.bodyMedium,
                            color =
                                MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val message = state.testMessage
                if (!state.testing && message != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (state.tested) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { setup.saveAndFinish() },
                    enabled = state.tested && !state.testing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save my key") }
                if (state.hasOwnKey) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Saving a new key replaces the one " +
                            "you have.",
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OwnKeyStep.DONE -> {
                StepCard(
                    title = "Your key is saved",
                    body = "OffLeash now signs in with your own " +
                        "Google key. One last thing: sign in once " +
                        "more, so your library can load under " +
                        "your key.",
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onFinished,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Go to sign in") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One wizard step's card: a title and its plain-language body. */
@Composable
private fun StepCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private const val PROJECT_URL =
    "https://console.cloud.google.com/projectcreate"
private const val API_URL =
    "https://console.cloud.google.com/apis/library/youtube.googleapis.com"
private const val CONSENT_URL =
    "https://console.cloud.google.com/apis/credentials/consent"
private const val CREATE_KEY_URL =
    "https://console.cloud.google.com/apis/credentials/oauthclient"
