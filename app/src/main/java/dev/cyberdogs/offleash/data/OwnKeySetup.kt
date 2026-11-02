package dev.cyberdogs.offleash.data

import android.content.Context
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.auth.DeviceCodeResult
import dev.cyberdogs.offleash.core.auth.GoogleDeviceAuth
import dev.cyberdogs.offleash.core.auth.OAuthClientIdentity
import dev.cyberdogs.offleash.core.auth.OAuthClientKind
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The owner's own Google key, entered in the app (0.8.6): the
 * stored form. "Key" is the app's plain word for the OAuth client
 * registration the owner creates in their own Google account —
 * the pair is a real credential, so this DTO only ever exists
 * sealed (see [OwnKeyStore]), never in plaintext, never in
 * diagnostics, and its class is always the owner's.
 */
@Serializable
data class StoredOwnKey(
    val clientId: String,
    val clientSecret: String,
)

/**
 * Where the own-key setup wizard stands (0.8.6): the step the
 * owner reached and the draft they pasted, sealed with the same
 * discipline as the key itself (the draft IS the key, mid-entry).
 * Persisted on every change so leaving mid-flow — closing the
 * app, answering a call — loses nothing: reopening the wizard
 * resumes on the same step with the same draft.
 */
@Serializable
data class StoredOwnKeyProgress(
    val stepOrdinal: Int = 0,
    val draftClientId: String = "",
    val draftClientSecret: String = "",
    val draftTested: Boolean = false,
)

private val ownKeyJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** The own-key file's name, pinned like the other stores'. */
const val OWN_KEY_FILE_NAME: String = "own_key.bin"

fun ownKeyFileIn(context: Context): File =
    File(context.noBackupFilesDir, OWN_KEY_FILE_NAME)

/** The wizard-progress file's name. */
const val OWN_KEY_WIZARD_FILE_NAME: String = "own_key_wizard.bin"

fun ownKeyWizardFileIn(context: Context): File =
    File(context.noBackupFilesDir, OWN_KEY_WIZARD_FILE_NAME)

/**
 * The sealed store for the owner's own key: built exactly like
 * [GoogleTokenStore], on the same [SecretStorage] and
 * [AesGcmSecretBox] machinery. [load] is total — any failure
 * reads as "no key", never a crash; [save] may throw and callers
 * must handle it, because a save that silently failed would
 * leave the app claiming a key nothing persisted.
 */
class OwnKeyStore(
    private val storage: SecretStorage,
    private val box: AesGcmSecretBox,
    private val legacyBox: AesGcmSecretBox? = null,
) {

    private val lock = Any()

    fun load(): StoredOwnKey? = synchronized(lock) {
        try {
            val blob = storage.read() ?: return null
            val plain = box.decrypt(blob) ?: legacyDecryptAndReseal(blob)
                ?: return null
            try {
                ownKeyJson.decodeFromString<StoredOwnKey>(
                    plain.toString(Charsets.UTF_8)
                )
            } catch (e: Exception) {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun save(key: StoredOwnKey) {
        synchronized(lock) {
            val plain = ownKeyJson.encodeToString(key)
                .toByteArray(Charsets.UTF_8)
            storage.write(box.encrypt(plain))
        }
    }

    fun wipe() {
        synchronized(lock) {
            try {
                storage.clear()
            } catch (e: Exception) {
                // Best effort, like the grant store's: a failed
                // clear surfaces as the key still loading, which
                // the state publish after a removal reports.
            }
        }
    }

    /**
     * The pre-0.8.7 seal, migrated (0.8.7 sweep 2): builds before
     * the alias split sealed this store under the SHARED session
     * key, whose regeneration — the session family's remedy —
     * would have destroyed this key with it. A blob the store's
     * own box cannot open is tried against the legacy box; a hit
     * is re-sealed under the own box before it is returned, so
     * the fallback is needed at most once. A re-seal failure is
     * not fatal: the value still loads, and the next launch
     * retries the migration.
     */
    private fun legacyDecryptAndReseal(blob: ByteArray): ByteArray? {
        val legacy = legacyBox ?: return null
        val plain = legacy.decrypt(blob) ?: return null
        try {
            storage.write(box.encrypt(plain))
        } catch (e: Exception) {
            // Migration retries next launch; the load stands.
        }
        return plain
    }
}

/** The sealed store for the wizard's progress — same discipline. */
class OwnKeyWizardStore(
    private val storage: SecretStorage,
    private val box: AesGcmSecretBox,
    private val legacyBox: AesGcmSecretBox? = null,
) {

    private val lock = Any()

    fun load(): StoredOwnKeyProgress? = synchronized(lock) {
        try {
            val blob = storage.read() ?: return null
            val plain = box.decrypt(blob) ?: legacyDecryptAndReseal(blob)
                ?: return null
            try {
                ownKeyJson.decodeFromString<StoredOwnKeyProgress>(
                    plain.toString(Charsets.UTF_8)
                )
            } catch (e: Exception) {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun save(progress: StoredOwnKeyProgress) {
        synchronized(lock) {
            val plain = ownKeyJson.encodeToString(progress)
                .toByteArray(Charsets.UTF_8)
            storage.write(box.encrypt(plain))
        }
    }

    fun wipe() {
        synchronized(lock) {
            try {
                storage.clear()
            } catch (e: Exception) {
                // Best effort — see [OwnKeyStore.wipe].
            }
        }
    }

    /** The wizard store's half of the alias migration — see [OwnKeyStore]. */
    private fun legacyDecryptAndReseal(blob: ByteArray): ByteArray? {
        val legacy = legacyBox ?: return null
        val plain = legacy.decrypt(blob) ?: return null
        try {
            storage.write(box.encrypt(plain))
        } catch (e: Exception) {
            // Migration retries next launch; the load stands.
        }
        return plain
    }
}

/**
 * The identity a sealed own key represents, or null when none is
 * stored: always the owner's class. The container resolves this
 * BEFORE the build-supplied pair at startup — a key the owner
 * entered on this device outranks anything a build carried in.
 */
fun storedOwnKeyIdentity(store: OwnKeyStore): OAuthClientIdentity? {
    val key = store.load() ?: return null
    if (key.clientId.isBlank() || key.clientSecret.isBlank()) return null
    return OAuthClientIdentity(
        clientId = key.clientId,
        clientSecret = key.clientSecret,
        kind = OAuthClientKind.OWNER,
    )
}

/** The wizard's steps, in order. DONE is the landing, not a chore. */
enum class OwnKeyStep {
    NEED,
    PROJECT,
    API,
    CONSENT,
    CREATE_KEY,
    ENTER_KEY,
    DONE,
}

/**
 * The paste screen's format checks (0.8.6): honest pre-checks that
 * catch a half-copied line before Google is asked. A Client ID is
 * a public shape — it ends in `.apps.googleusercontent.com` — and
 * a secret is a long unbroken string. These checks never claim a
 * key WORKS: only the wizard's live test against Google says that.
 */
object OwnKeyValidation {

    fun clientIdError(clientId: String): String? {
        val id = clientId.trim()
        if (id.isEmpty()) return "The Client ID is missing."
        if (!id.endsWith(".apps.googleusercontent.com")) {
            return "That Client ID doesn't look complete — it " +
                "should end in .apps.googleusercontent.com. Check " +
                "you copied the whole line."
        }
        return null
    }

    fun clientSecretError(clientSecret: String): String? {
        val secret = clientSecret.trim()
        if (secret.isEmpty()) return "The Client secret is missing."
        if (secret.length < 10 || secret.contains(" ")) {
            return "That Client secret doesn't look complete — " +
                "check you copied the whole line, with no spaces."
        }
        return null
    }

    fun isValid(clientId: String, clientSecret: String): Boolean =
        clientIdError(clientId) == null &&
            clientSecretError(clientSecret) == null
}

/**
 * The own-key setup wizard's brain (0.8.6): the step machine, the
 * sealed persistence of progress + draft, the live key test, and
 * the save that switches sign-in to the owner's pair. The screen
 * is a pure rendering of [state]; every button on it lands here.
 *
 * The precedence this class maintains: a saved own key outranks
 * the build-supplied identity ([buildIdentity] is what sign-in
 * falls back to when the key is removed). The save and the
 * removal both run the session manager's client-change gate, so
 * a session sealed under the old client ends cleanly, once, with
 * its honest reason — never as a silent wipe or an error loop.
 */
class OwnKeySetup(
    private val keyStore: OwnKeyStore,
    private val wizardStore: OwnKeyWizardStore,
    private val transport: HttpTransport,
    private val auth: GoogleDeviceAuth,
    private val sessionManager: GoogleSessionManager,
    private val buildIdentity: OAuthClientIdentity?,
    private val scope: CoroutineScope,
    private val signInDiagnostics: SignInDiagnostics,
) {

    /** What the wizard screen renders. */
    data class UiState(
        val step: OwnKeyStep = OwnKeyStep.NEED,
        val draftClientId: String = "",
        val draftClientSecret: String = "",
        /** The current draft passed its live test against Google. */
        val tested: Boolean = false,
        /** A live test is running right now. */
        val testing: Boolean = false,
        /** The last test's verdict, in plain words. */
        val testMessage: String? = null,
        /** A key is saved and sign-in runs under it. */
        val hasOwnKey: Boolean = false,
    )

    private var current: UiState = restoreInitialState()

    /**
     * The live test's generation (0.8.7 sweep 1): every transition
     * that changes what a test would be testing — a draft edit, a
     * restart, a save, a removal — bumps it and cancels the job,
     * so a verdict can only ever land on the exact pair it tested.
     */
    private var testGeneration: Long = 0
    private var testJob: Job? = null

    private fun invalidateTest() {
        testGeneration++
        testJob?.cancel()
        testJob = null
    }

    private val _state = MutableStateFlow(current)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private fun restoreInitialState(): UiState {
        val progress = wizardStore.load()
        val restoredStep = if (progress == null) {
            OwnKeyStep.NEED
        } else {
            val steps = OwnKeyStep.values()
            val ordinal = progress.stepOrdinal
            if (ordinal < 0 || ordinal >= steps.size) {
                OwnKeyStep.NEED
            } else {
                val step = steps[ordinal]
                // A persisted DONE is a finished wizard: reopening
                // starts at the top, where the key's management
                // lives — not on a stale landing card.
                if (step == OwnKeyStep.DONE) OwnKeyStep.NEED else step
            }
        }
        return UiState(
            step = restoredStep,
            draftClientId = progress?.draftClientId ?: "",
            draftClientSecret = progress?.draftClientSecret ?: "",
            // The draft resumes; its test verdict does not (0.8.7
            // sweep 1). A pass is a statement about a moment — the
            // owner may have rotated or deleted the key in the
            // console since — so a restarted wizard always asks
            // for a fresh test before a save.
            tested = false,
            // One definition of "a key is set", shared with the
            // identity resolution: a stored record with a blank
            // half is not a key (0.8.7 sweep 1).
            hasOwnKey = storedOwnKeyIdentity(keyStore) != null,
        )
    }

    /** The single state-write site: publish + persist, together. */
    private fun publish(next: UiState) {
        current = next
        _state.value = next
        try {
            wizardStore.save(
                StoredOwnKeyProgress(
                    stepOrdinal = next.step.ordinal,
                    draftClientId = next.draftClientId,
                    draftClientSecret = next.draftClientSecret,
                    draftTested = next.tested,
                ),
            )
        } catch (e: Exception) {
            // Progress is a convenience, not the key itself: a
            // failed persist is logged, never a crash, and the
            // in-memory state still carries the owner forward.
            signInDiagnostics.log(
                "own-key",
                "the setup progress couldn't be saved " +
                    "(${e.javaClass.simpleName}) — continuing anyway",
            )
        }
    }

    /** One step forward (never past the paste screen). */
    fun advance() {
        val steps = OwnKeyStep.values()
        val nextOrdinal = current.step.ordinal + 1
        if (nextOrdinal > OwnKeyStep.ENTER_KEY.ordinal) return
        publish(current.copy(step = steps[nextOrdinal]))
    }

    /** One step back (never before the first). */
    fun back() {
        val steps = OwnKeyStep.values()
        val nextOrdinal = current.step.ordinal - 1
        if (nextOrdinal < 0) return
        publish(current.copy(step = steps[nextOrdinal]))
    }

    /** Start over: the draft and the place are both cleared. */
    fun restart() {
        invalidateTest()
        wizardStore.wipe()
        publish(
            UiState(hasOwnKey = current.hasOwnKey),
        )
    }

    /**
     * The paste screen's fields changed: any edit un-tests the
     * draft — a key is only ever saved in the exact form that
     * passed its test.
     */
    fun updateDraft(clientId: String, clientSecret: String) {
        if (clientId == current.draftClientId &&
            clientSecret == current.draftClientSecret
        ) {
            return
        }
        invalidateTest()
        publish(
            current.copy(
                draftClientId = clientId,
                draftClientSecret = clientSecret,
                tested = false,
                testing = false,
                testMessage = null,
            ),
        )
    }

    /**
     * "Test my key": a REAL device-code request to Google with
     * the entered pair — the same first call a sign-in makes. A
     * pass means Google accepted the pair and issued a code; the
     * issued code is deliberately discarded (unapproved device
     * codes grant nothing and expire on their own), because the
     * owner's real sign-in starts fresh after the save. A failure
     * saves nothing and says so in plain words; the raw detail
     * rides diagnostics only.
     */
    fun testKey() {
        if (current.testing) return
        val id = current.draftClientId.trim()
        val secret = current.draftClientSecret.trim()
        if (!OwnKeyValidation.isValid(id, secret)) return
        val generation = ++testGeneration
        publish(current.copy(testing = true, testMessage = null))
        testJob = scope.launch {
            val entered = OAuthClientIdentity(
                clientId = id,
                clientSecret = secret,
                kind = OAuthClientKind.OWNER,
            )
            val result = try {
                withContext(Dispatchers.IO) {
                    GoogleDeviceAuth(transport, entered).requestDeviceCode()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The device-code call is total for Exceptions;
                // an escaping Error must still land as a failed
                // test with the spinner cleared, never as a stuck
                // wizard or a dead process (0.8.7 sweep 1).
                if (generation == testGeneration) {
                    signInDiagnostics.log(
                        "own-key",
                        "the key test couldn't finish " +
                            "(${e.javaClass.simpleName}) — " +
                            "nothing was saved",
                    )
                    publish(
                        current.copy(
                            testing = false,
                            tested = false,
                            testMessage = TEST_FAILED_MESSAGE,
                        ),
                    )
                }
                return@launch
            }
            // A superseded test says nothing: the transition that
            // replaced it already reset the state it owns.
            if (generation != testGeneration) return@launch
            when (result) {
                is DeviceCodeResult.Issued -> {
                    signInDiagnostics.log(
                        "own-key",
                        "the entered key passed its live test — " +
                            "Google issued a sign-in code for it",
                    )
                    publish(
                        current.copy(
                            testing = false,
                            tested = true,
                            testMessage = TEST_PASSED_MESSAGE,
                        ),
                    )
                }
                is DeviceCodeResult.Failed -> {
                    signInDiagnostics.log(
                        "own-key",
                        "the entered key failed its live test " +
                            "(${result.detail}) — nothing was saved",
                    )
                    publish(
                        current.copy(
                            testing = false,
                            tested = false,
                            testMessage = TEST_FAILED_MESSAGE,
                        ),
                    )
                }
            }
        }
    }

    /**
     * "Save my key" — enabled only for a draft that passed its
     * test, in the exact form tested. Persists the pair sealed,
     * switches the live sign-in client to it, and runs the
     * session gate: a session sealed under the previous client
     * ends cleanly here, and the owner lands on sign-in to
     * reconnect — once, with the reason on the screen.
     */
    fun saveAndFinish() {
        if (!current.tested) return
        val id = current.draftClientId.trim()
        val secret = current.draftClientSecret.trim()
        if (!OwnKeyValidation.isValid(id, secret)) return
        invalidateTest()
        val enteredIdentity = OAuthClientIdentity(
            clientId = id,
            clientSecret = secret,
            kind = OAuthClientKind.OWNER,
        )
        // The gate question is whether the LIVE client changes —
        // compared as a whole identity, not by stored record
        // (0.8.7 sweep 1): a secret-only rotation under the same
        // Client ID, and a first save over a build-supplied owner
        // pair, both change which registration the standing
        // session's tokens belong to, and both must end it.
        val pairChanged = auth.identity != enteredIdentity
        try {
            keyStore.save(StoredOwnKey(clientId = id, clientSecret = secret))
        } catch (e: Exception) {
            // A save that failed must never read as a save that
            // happened: nothing switches, the draft stands tested,
            // and the owner can simply tap Save again.
            signInDiagnostics.log(
                "own-key",
                "the key couldn't be saved on this device " +
                    "(${e.javaClass.simpleName}) — nothing changed",
            )
            publish(
                current.copy(
                    testing = false,
                    testMessage = SAVE_FAILED_MESSAGE,
                ),
            )
            return
        }
        auth.identity = enteredIdentity
        sessionManager.applyClientGate(forceCleanResign = pairChanged)
        signInDiagnostics.log(
            "own-key",
            "the owner's key was saved — sign-in now runs under it",
        )
        // The wizard's work is done: the draft is SPENT, not kept
        // (0.8.7 sweep 1). Publish the landing with the draft
        // cleared, then wipe the progress file — in this order,
        // because publish persists what it is given, and an
        // earlier build's wipe-then-publish quietly re-saved the
        // live pair into the progress file it had just wiped.
        publish(
            current.copy(
                step = OwnKeyStep.DONE,
                draftClientId = "",
                draftClientSecret = "",
                tested = false,
                testing = false,
                hasOwnKey = true,
            ),
        )
        wizardStore.wipe()
    }

    /**
     * Removes the saved key: sign-in falls back to whatever the
     * build itself carries (its supplied pair, or nothing), and
     * the gate ends a session the fallback can't refresh.
     */
    fun removeOwnKey() {
        invalidateTest()
        keyStore.wipe()
        // Falling back changes the live client whenever the build
        // pair is a different registration than the removed key —
        // the gate hears about it exactly like a save (0.8.7
        // sweep 1).
        val pairChanged = auth.identity != buildIdentity
        auth.identity = buildIdentity
        sessionManager.applyClientGate(forceCleanResign = pairChanged)
        signInDiagnostics.log(
            "own-key",
            "the owner's key was removed — sign-in fell back " +
                "to the build's own setup",
        )
        // Removal removes (0.8.7 sweep 1): the wizard draft is the
        // same pair in a second sealed file, so it is cleared and
        // wiped too — an earlier build left it in place and the
        // "removed" key reappeared on the paste screen.
        publish(
            current.copy(
                step = OwnKeyStep.NEED,
                draftClientId = "",
                draftClientSecret = "",
                tested = false,
                testing = false,
                testMessage = null,
                hasOwnKey = false,
            ),
        )
        wizardStore.wipe()
    }

    companion object {

        /**
         * The live test's pass verdict, shown on the paste screen:
         * scoped to what the test actually proves — Google accepted
         * the pair for a sign-in code. It does not prove the
         * earlier steps (the API switch, the Publish step), and it
         * says so, so a later mystery sign-out has a named first
         * suspect instead of contradicting a "your key works"
         * verdict (0.8.7 sweep 3).
         */
        const val TEST_PASSED_MESSAGE =
            "Google accepted your key — the ID and secret are " +
                "right. This test checks the key itself; it can't " +
                "check steps 3 and 4, so if Google signs you out " +
                "every week later, those are the steps to re-check."

        /**
         * The live test's failure verdict: the copy causes first,
         * then the other usual suspects, with the steps named so
         * Back can take the owner straight to them (0.8.7 sweep 3).
         */
        const val TEST_FAILED_MESSAGE =
            "Google didn't accept that key. First check both " +
                "lines are copied exactly from your key page, then " +
                "test again. Still failing? The usual causes: in " +
                "step 5 the type must be “TVs and Limited Input " +
                "devices”, and step 4 must end with “Publish app”. " +
                "Your key wasn't saved."

        /** The save-failure verdict: the key never reached storage. */
        const val SAVE_FAILED_MESSAGE =
            "Your key couldn't be saved on this device — " +
                "nothing was changed. Try saving again."
    }
}
