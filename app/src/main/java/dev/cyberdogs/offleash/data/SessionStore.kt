package dev.cyberdogs.offleash.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Where the encrypted session bytes live, behind an interface so the
 * store is testable without a device filesystem.
 */
interface SecretStorage {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun clear()
}

/**
 * One file on disk. [clear] is a real wipe, not just a delete: the
 * file is overwritten with zeros at its full length first, then
 * deleted, so the ciphertext does not linger in freed blocks.
 */
class FileSecretStorage(private val file: File) : SecretStorage {

    override fun read(): ByteArray? {
        return try {
            if (!file.isFile) {
                null
            } else {
                file.readBytes().takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun write(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    override fun clear() {
        try {
            if (file.isFile) {
                val length = file.length()
                if (length > 0) {
                    java.io.RandomAccessFile(file, "rw").use { raf ->
                        raf.setLength(length)
                        raf.seek(0)
                        raf.write(ByteArray(length.toInt()) { 0 })
                        raf.fd.sync()
                    }
                }
                file.delete()
            }
        } catch (e: Exception) {
            // Best effort: still try the plain delete below via a
            // second attempt in the caller's verify step; [SessionStore
            // .wipe] reports whether the bytes are actually gone.
            try {
                file.delete()
            } catch (ignored: Exception) {
                // Nothing more can be done from here; wipe() verifies.
            }
        }
    }
}

/** In-memory [SecretStorage] for tests — no disk, no Android. */
class InMemorySecretStorage : SecretStorage {

    private var bytes: ByteArray? = null

    override fun read(): ByteArray? = bytes?.copyOf()

    override fun write(bytes: ByteArray) {
        this.bytes = bytes.copyOf()
    }

    override fun clear() {
        bytes?.fill(0)
        bytes = null
    }
}

/**
 * The stored form of a signed-in session. This DTO is what gets
 * encrypted — it is never written anywhere in this (plaintext) shape.
 *
 * Identity fields are kept beside the credential so the UI can show
 * who is signed in without asking the network, and so validation can
 * match the stored identity against the switcher's enumeration.
 */
@Serializable
data class StoredSession(
    val cookies: Map<String, String>,
    val authUser: Int = 0,
    val pageId: String? = null,
    val visitorData: String? = null,
    val identityName: String? = null,
    val identityEmail: String? = null,
    val avatarUrl: String? = null,
    val capturedAtEpochMs: Long = 0L,
    val lastValidatedEpochMs: Long = 0L,
)

private val sessionJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** The session file's name, pinned so contract tests can assert it. */
const val SESSION_FILE_NAME: String = "session.bin"

/**
 * The file the session physically lives in: the app's no-backup
 * directory, which the platform itself excludes from backup and from
 * device transfer on every API level. The manifest's backup rules
 * exclude the same path a second time (defense in depth — see
 * res/xml/backup_rules.xml and res/xml/data_extraction_rules.xml).
 */
fun sessionFileIn(context: Context): File = File(context.noBackupFilesDir, SESSION_FILE_NAME)

/**
 * The single, encrypted store for the YouTube session.
 *
 * The ONLY bytes that ever reach [storage] are
 * `box.encrypt(json)` output — no plaintext cookie, identity, or
 * email ever touches disk (a test asserts exactly that). Every read
 * failure (missing file, bad key, corrupt bytes, changed shape)
 * reads as "no session", never as a crash.
 *
 * Synchronous and internally locked by design; coroutine discipline
 * (which thread calls happen on) is the SessionManager's job.
 */
class SessionStore(
    private val storage: SecretStorage,
    private val box: AesGcmSecretBox,
) {

    private val lock = Any()

    fun load(): StoredSession? = synchronized(lock) {
        val blob = storage.read() ?: return null
        val plain = box.decrypt(blob) ?: return null
        try {
            sessionJson.decodeFromString<StoredSession>(plain.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    fun save(session: StoredSession) {
        synchronized(lock) {
            val plain = sessionJson.encodeToString(session).toByteArray(Charsets.UTF_8)
            storage.write(box.encrypt(plain))
        }
    }

    /**
     * Destroys the stored session and VERIFIES the destruction:
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
