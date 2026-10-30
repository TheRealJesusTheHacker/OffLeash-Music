package dev.cyberdogs.offleash.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A device-code grant that has been issued but not yet approved
 * (0.7.1): everything the sign-in needs to SURVIVE the app being
 * backgrounded, swiped away, or killed while the owner is in the
 * browser approving it. Persisted the moment Google issues it, so
 * the poll for it can resume after any restart — the 0.7.0 defect
 * was that this grant existed only as a local variable inside the
 * poll coroutine, and process death orphaned it: the owner
 * approved a code no process would ever poll again.
 *
 * The [deviceCode] is credential-adjacent: whoever holds it can
 * collect the granted tokens, so it gets the same custody as the
 * tokens themselves — this DTO is only ever written sealed (see
 * [GooglePendingGrantStore]), into its own file beside the token
 * store's, never in plaintext and never to diagnostics. It is also
 * short-lived by construction: it is wiped the instant the grant
 * resolves (approved, declined, expired, or cancelled).
 */
@Serializable
data class StoredPendingGrant(
    /** The poll handle Google issued. Never logged, never shown. */
    val deviceCode: String,
    /** The short code the owner types at the verification URL. */
    val userCode: String,
    val verificationUrl: String,
    /** When Google issued the grant, epoch milliseconds. */
    val issuedAtEpochMs: Long,
    /** When the grant dies unapproved, epoch milliseconds. */
    val expiresAtEpochMs: Long,
    /** Google's minimum poll interval, seconds. */
    val intervalSeconds: Long,
) {
    /** True while the grant can still be approved and polled. */
    fun isLiveAt(nowEpochMs: Long): Boolean = nowEpochMs < expiresAtEpochMs
}

private val pendingGrantJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** The pending-grant file's name, pinned like the token store's. */
const val GOOGLE_PENDING_GRANT_FILE_NAME: String = "google_pending_grant.bin"

/**
 * The file the pending grant physically lives in: the app's
 * no-backup directory, beside the Google token file — excluded
 * from backup and device transfer the same way.
 */
fun googlePendingGrantFileIn(context: Context): File =
    File(context.noBackupFilesDir, GOOGLE_PENDING_GRANT_FILE_NAME)

/**
 * The single, encrypted store for the pending device-code grant.
 *
 * Built exactly like [GoogleTokenStore], on the same [SecretStorage]
 * and [AesGcmSecretBox] machinery: the only bytes that reach
 * storage are `box.encrypt(json)` output. Every read failure
 * (missing file, bad key, corrupt bytes, changed shape) reads as
 * "no pending grant", never as a crash.
 */
class GooglePendingGrantStore(
    private val storage: SecretStorage,
    private val box: AesGcmSecretBox,
) {

    private val lock = Any()

    /**
     * Total by contract, like [GoogleTokenStore.load] (H3, 0.7.2
     * sweep): any read/seal/decode failure reads as "no pending
     * grant", never a throw. [save] may still throw — its caller
     * (the session manager) must know, because it publishes the
     * code only with the durability caveat recorded.
     */
    fun load(): StoredPendingGrant? = synchronized(lock) {
        try {
            val blob = storage.read() ?: return null
            val plain = box.decrypt(blob) ?: return null
            try {
                pendingGrantJson.decodeFromString<StoredPendingGrant>(
                    plain.toString(Charsets.UTF_8)
                )
            } catch (e: Exception) {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun save(grant: StoredPendingGrant) {
        synchronized(lock) {
            val plain = pendingGrantJson.encodeToString(grant)
                .toByteArray(Charsets.UTF_8)
            storage.write(box.encrypt(plain))
        }
    }

    /**
     * Destroys the stored grant and VERIFIES the destruction:
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
