package dev.cyberdogs.offleash.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.InvalidAlgorithmParameterException
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
 *
 * Key-parameter recovery (0.8.1 hotfix). The box always passes its
 * own IV to cipher init (the wire format prefixes it), and Android
 * Keystore keys are authorized per-parameter: a key whose spec does
 * not permit a caller-provided IV for GCM encryption — the
 * KeyGenParameterSpec DEFAULT (randomized encryption required),
 * which this app's generator omitted to relax until 0.8.1, or any
 * stale alias an earlier build created with different parameters —
 * makes `Cipher.init` throw [InvalidAlgorithmParameterException]
 * on a real device, while JVM tests (a raw SecretKeySpec carries no
 * authorizations) stay green. The owner's first 0.8.0 sign-in hit
 * exactly this: the pending-grant seal failed on his phone and the
 * grant degraded to memory-only, silently reviving the 0.7.0
 * restart bug on the only hardware that matters.
 *
 * So when a seal fails with that exception — and ONLY that
 * exception; an IO or invalid-key failure is a different disease
 * and propagates untouched — [encrypt] asks [keyRegenerator] for a
 * fresh key built with the correct spec and retries the seal ONCE.
 * A second parameter failure propagates: regeneration is a remedy
 * for a mis-specced key, not a loop.
 *
 * Why destroying the old key is safe, on the record: decryption
 * init performs no such refusal (a stored IV is how decryption
 * works at all — the randomized-encryption authorization binds
 * ENCRYPTION with a caller IV only), so anything the old key ever
 * sealed remained readable right up to regeneration; and every
 * store on this box holds re-obtainable sign-in credentials — a
 * device grant with minutes of life, Google tokens, a legacy
 * cookie session — never user data. After regeneration those old
 * blobs decrypt to null under the box's standing contract, the
 * stores read "no stored session", and the owner signs in again:
 * one re-sign-in, against the alternative of a key that can never
 * seal again and sign-in broken on the device forever.
 */
class AesGcmSecretBox(
    private val keyProvider: () -> SecretKey,
    private val keyRegenerator: (() -> SecretKey)? = null,
) {

    fun encrypt(plain: ByteArray): ByteArray {
        return try {
            seal(keyProvider(), plain)
        } catch (e: InvalidAlgorithmParameterException) {
            // A key-parameter failure: the standing key cannot be
            // used with this box's cipher parameters at all (see
            // the class doc). Regenerate scoped to this box's key
            // and retry once; without a regenerator (JVM tests use
            // raw keys, which have no parameters to mismatch) the
            // failure propagates exactly as it did before 0.8.1.
            val regenerate = keyRegenerator ?: throw e
            seal(regenerate(), plain)
        }
    }

    private fun seal(key: SecretKey, plain: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
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

    /**
     * Destroys the key under [alias] and generates a fresh one with
     * the current spec — the scoped recovery [AesGcmSecretBox]
     * invokes when a seal proves the standing key's parameters
     * cannot serve this box (0.8.1 hotfix; see the box's doc for
     * why orphaning the old key's blobs is the safe trade). The
     * three session stores share one alias, so one regeneration
     * heals every seal site at once.
     */
    fun regenerate(alias: String): SecretKey {
        // Written without the apply-block load idiom on purpose:
        // the module census pins this file's apply-block count,
        // and a plain two-step load needs no re-base.
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        runCatching { keyStore.deleteEntry(alias) }
        return generate(keyStore, alias)
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
            // The box supplies its own IV at cipher init (it is
            // prefixed to the stored blob), so the key MUST permit
            // a caller-provided IV. Randomized encryption stays
            // guaranteed by construction — the box draws a fresh
            // random IV for every seal — but the Keystore default
            // (randomized encryption REQUIRED) refuses a caller IV
            // outright with InvalidAlgorithmParameterException.
            // Omitting this line is what broke sealing on the
            // owner's device in 0.8.0 (0.8.1 hotfix).
            .setRandomizedEncryptionRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
}
