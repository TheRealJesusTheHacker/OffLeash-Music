package dev.cyberdogs.offleash.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A native sign-in attempt that has been started but not yet
 * answered (0.8.8): everything the sign-in needs to SURVIVE the app
 * being backgrounded, swiped away, or killed while the owner is in
 * the browser approving it. Persisted the moment the attempt is
 * built, so its return can still be collected after any restart —
 * the browser leg runs outside the app, and process death must not
 * orphan an approval the owner already gave.
 *
 * The [codeVerifier] is credential-adjacent: whoever holds it plus
 * the returned code can collect the granted tokens, so it gets the
 * same custody as the tokens themselves — this DTO is only ever
 * written sealed (see [GooglePendingNativeAuthStore]), into its
 * own file beside the token store's, never in plaintext and never
 * to diagnostics. It is also short-lived by construction: it is
 * wiped the instant the attempt resolves (adopted, declined,
 * mismatched, expired, or cancelled) and it dies of old age at
 * [expiresAtEpochMs] regardless.
 */
@Serializable
data class StoredPendingNativeAuth(
    /** The attempt's state binding: the return must echo it. Never logged. */
    val state: String,
    /** The PKCE verifier the code exchange presents. Never logged. */
    val codeVerifier: String,
    /**
     * The Android client ID the attempt was minted under: a public
     * identifier, recorded so the client-change gate can refuse to
     * collect an attempt minted under a different registration.
     */
    val clientId: String,
    /** Where Google sends the owner back to. */
    val redirectUri: String,
    /** When the attempt was built, epoch milliseconds. */
    val issuedAtEpochMs: Long,
    /** When the attempt dies unanswered, epoch milliseconds. */
    val expiresAtEpochMs: Long,
) {
    /** True while the attempt's return can still be collected. */
    fun isLiveAt(nowEpochMs: Long): Boolean = nowEpochMs < expiresAtEpochMs
}

private val pendingNativeAuthJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** The pending native attempt file's name, pinned like the token store's. */
const val GOOGLE_PENDING_NATIVE_AUTH_FILE_NAME: String =
    "google_pending_native_auth.bin"

/**
 * The file the pending native attempt physically lives in: the
 * app's no-backup directory, beside the Google token file —
 * excluded from backup and device transfer the same way.
 */
fun googlePendingNativeAuthFileIn(context: Context): File =
    File(context.noBackupFilesDir, GOOGLE_PENDING_NATIVE_AUTH_FILE_NAME)

/**
 * The single, encrypted store for the pending native sign-in
 * attempt.
 *
 * Built exactly like [GoogleTokenStore], on the same
 * [SecretStorage] and [AesGcmSecretBox] machinery: the only bytes
 * that reach storage are `box.encrypt(json)` output. Every read
 * failure (missing file, bad key, corrupt bytes, changed shape)
 * reads as "no pending attempt", never as a crash.
 */
class GooglePendingNativeAuthStore(
    private val storage: SecretStorage,
    private val box: AesGcmSecretBox,
) {

    private val lock = Any()

    /**
     * Total by contract, like [GoogleTokenStore.load]: any
     * read/seal/decode failure reads as "no pending attempt",
     * never a throw. [save] may still throw — its caller (the
     * session manager) must know, because it publishes the attempt
     * only with the durability caveat recorded.
     */
    fun load(): StoredPendingNativeAuth? = synchronized(lock) {
        try {
            val blob = storage.read() ?: return null
            val plain = box.decrypt(blob) ?: return null
            try {
                pendingNativeAuthJson.decodeFromString<StoredPendingNativeAuth>(
                    plain.toString(Charsets.UTF_8)
                )
            } catch (e: Exception) {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun save(attempt: StoredPendingNativeAuth) {
        synchronized(lock) {
            val plain = pendingNativeAuthJson.encodeToString(attempt)
                .toByteArray(Charsets.UTF_8)
            storage.write(box.encrypt(plain))
        }
    }

    /**
     * Destroys the stored attempt and VERIFIES the destruction:
     * returns true only when a read-back finds nothing left. Total
     * like [load]: a storage failure reports false, never a throw.
     */
    fun wipe(): Boolean = synchronized(lock) {
        try {
            storage.clear()
        } catch (e: Exception) {
            // The read-back below is the verdict either way.
        }
        isWipedClean()
    }

    /** True when nothing is stored. */
    fun isWipedClean(): Boolean = synchronized(lock) {
        try {
            storage.read() == null
        } catch (e: Exception) {
            false
        }
    }
}
