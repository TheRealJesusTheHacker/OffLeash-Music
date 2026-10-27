package dev.cyberdogs.offleash.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encryption for the one credential this app stores.
 *
 * Wire format: a 12-byte random IV, prepended to the GCM ciphertext
 * (which carries its own authentication tag). Bytes in, bytes out —
 * no Base64, no string form anywhere, so a secret value can never end
 * up rendered into a log line or a UI surface by accident.
 *
 * [decrypt] never throws: any failure (tampered bytes, wrong key,
 * truncated blob) reads as null, and callers treat null as "no stored
 * session" — a corrupt store signs the user out, it never crashes.
 *
 * The box takes its key from a provider lambda so the cipher logic is
 * plain-JVM testable with a locally generated key, while production
 * supplies an Android Keystore key via [KeystoreSecretKey].
 */
class AesGcmSecretBox(
    private val keyProvider: () -> SecretKey,
) {

    fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plain)
        return iv + ciphertext
    }

    fun decrypt(blob: ByteArray): ByteArray? {
        return try {
            if (blob.size <= GCM_IV_BYTES) return null
            val iv = blob.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = blob.copyOfRange(GCM_IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            // Tampered, truncated, or sealed under a different key.
            null
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}

/**
 * The production key source: an AES-256 key that lives inside the
 * Android Keystore and never leaves it in plaintext. No user-auth
 * requirement — the key must be usable by the keep-alive worker while
 * the device is locked, which a biometric-gated key would refuse.
 */
object KeystoreSecretKey {

    fun getOrCreate(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        return try {
            generate(keyStore, alias)
        } catch (e: Exception) {
            // First-generation faults are a known device class: a
            // half-created entry or a transient provider failure on
            // the very first key this app has ever asked for. At this
            // point the alias has never sealed anything — no stored
            // session can depend on a key that does not exist yet —
            // so clearing any partial entry and generating once more
            // cannot orphan data. A second failure propagates: the
            // caller (session adoption) now degrades to the sign-in
            // diagnostics card naming the store stage instead of
            // crashing (2026-10-27 crash hunt — the first confirmed
            // sign-in reaches this code for the first time, and
            // before the hunt nothing caught its failure).
            runCatching { keyStore.deleteEntry(alias) }
            generate(keyStore, alias)
        }
    }

    private fun generate(keyStore: KeyStore, alias: String): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
}
