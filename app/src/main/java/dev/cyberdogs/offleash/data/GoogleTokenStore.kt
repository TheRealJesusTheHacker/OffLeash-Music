package dev.cyberdogs.offleash.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The stored form of a Google sign-in (0.7.0): the OAuth token set
 * plus the identity Google confirmed for it. This DTO is what gets
 * encrypted — it is never written anywhere in this (plaintext)
 * shape, and it lives in its own sealed file beside (never inside)
 * the cookie session's, so the two sign-in generations can never
 * corrupt each other.
 */
@Serializable
data class StoredGoogleSession(
    val accessToken: String,
    val refreshToken: String? = null,
    /** When the access token stops working, epoch milliseconds. */
    val accessTokenExpiresAtEpochMs: Long = 0L,
    /** The scope string Google granted, verbatim, for the record. */
    val grantedScopes: String? = null,
    val identityName: String? = null,
    val identityEmail: String? = null,
    val avatarUrl: String? = null,
    val grantedAtEpochMs: Long = 0L,
    val lastValidatedEpochMs: Long = 0L,
)

private val googleSessionJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** The Google session file's name, pinned like the cookie session's. */
const val GOOGLE_SESSION_FILE_NAME: String = "google_session.bin"

/**
 * The file the Google tokens physically live in: the app's
 * no-backup directory, same as the cookie session — the platform
 * excludes it from backup and device transfer on every API level,
 * and the manifest's backup rules exclude the same directory a
 * second time (see res/xml/backup_rules.xml).
 */
fun googleSessionFileIn(context: Context): File =
    File(context.noBackupFilesDir, GOOGLE_SESSION_FILE_NAME)

/**
 * The single, encrypted store for the Google OAuth tokens.
 *
 * Built exactly like [SessionStore], on the same [SecretStorage]
 * and [AesGcmSecretBox] machinery: the ONLY bytes that ever reach
 * storage are `box.encrypt(json)` output — no plaintext token,
 * identity, or email ever touches disk. Every read failure
 * (missing file, bad key, corrupt bytes, changed shape) reads as
 * "no Google session", never as a crash.
 */
class GoogleTokenStore(
    private val storage: SecretStorage,
    private val box: AesGcmSecretBox,
) {

    private val lock = Any()

    fun load(): StoredGoogleSession? = synchronized(lock) {
        val blob = storage.read() ?: return null
        val plain = box.decrypt(blob) ?: return null
        try {
            googleSessionJson.decodeFromString<StoredGoogleSession>(
                plain.toString(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            null
        }
    }

    fun save(session: StoredGoogleSession) {
        synchronized(lock) {
            val plain = googleSessionJson.encodeToString(session)
                .toByteArray(Charsets.UTF_8)
            storage.write(box.encrypt(plain))
        }
    }

    /**
     * Destroys the stored tokens and VERIFIES the destruction:
     * returns true only when a read-back finds nothing left.
     */
    fun wipe(): Boolean = synchronized(lock) {
        storage.clear()
        storage.read() == null
    }

    /** True when nothing is stored — the post-sign-out ground truth. */
    fun isWipedClean(): Boolean = synchronized(lock) {
        storage.read() == null
    }
}
