package dev.cyberdogs.offleash.data

import java.io.IOException
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The AES-GCM box on a locally generated key (no Android Keystore on
 * the JVM): round-trip, tamper detection, and wrong-key refusal.
 */
class SecretBoxTest {

    @Test
    fun roundTripReturnsThePlaintext() {
        val box = testSecretBox()
        val plain = "SAPISID=some-session-value; SID=another".toByteArray(Charsets.UTF_8)
        val blob = box.encrypt(plain)
        // The blob is IV + ciphertext: never the plaintext itself.
        assertFalse(blob.contentEquals(plain))
        assertArrayEquals(plain, box.decrypt(blob))
    }

    @Test
    fun tamperedCiphertextDecryptsToNull() {
        val box = testSecretBox()
        val blob = box.encrypt("session".toByteArray(Charsets.UTF_8))
        val tampered = blob.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0x01).toByte()
        assertNull(box.decrypt(tampered))
        // A flipped IV byte is tampering too (the tag no longer verifies).
        val ivTampered = blob.copyOf()
        ivTampered[0] = (ivTampered[0].toInt() xor 0x01).toByte()
        assertNull(box.decrypt(ivTampered))
        // And a truncated blob is a null, never a crash.
        assertNull(box.decrypt(blob.copyOfRange(0, 8)))
        assertNull(box.decrypt(ByteArray(0)))
    }

    @Test
    fun wrongKeyDecryptsToNull() {
        val box = testSecretBox()
        val otherBox = testSecretBox()
        val blob = box.encrypt("session".toByteArray(Charsets.UTF_8))
        assertNull(otherBox.decrypt(blob))
    }

    // ------------------------------------------------------------------
    // 0.8.1 hotfix: key-parameter recovery. On the owner's device the
    // standing Keystore key's parameters refuse this box's cipher
    // init (InvalidAlgorithmParameterException) — JVM raw keys carry
    // no authorizations, so the failure is simulated by a key source
    // that throws the same exception type the Android provider
    // throws from Cipher.init. The box must regenerate ONCE, retry
    // the seal, and leave every other failure class untouched.
    // ------------------------------------------------------------------

    /**
     * A key source standing in for a mis-specced Keystore alias:
     * [key] throws [failure] until [regenerate] "rebuilds" it, after
     * which the real key serves. Counts regenerations.
     */
    private class ScriptedKeySource(
        var failure: () -> Exception = {
            InvalidAlgorithmParameterException("caller-provided IV refused by key spec")
        },
    ) {
        val realKey: SecretKey = testSecretKey()
        var regenerated = false
        var regenerations = 0

        fun key(): SecretKey {
            if (!regenerated) throw failure()
            return realKey
        }

        fun regenerate(): SecretKey {
            regenerations++
            regenerated = true
            return realKey
        }
    }

    @Test
    fun aParameterFailureRegeneratesOnceAndTheRetriedSealPersists() {
        val source = ScriptedKeySource()
        val box = AesGcmSecretBox(source::key, source::regenerate)
        val plain = "device-code-grant".toByteArray(Charsets.UTF_8)
        val blob = box.encrypt(plain)
        assertEquals(1, source.regenerations)
        // The retried seal is a real seal under the regenerated
        // key: the same box (whose provider now serves the new key,
        // exactly like getOrCreate after a regenerate) reads it back.
        assertArrayEquals(plain, box.decrypt(blob))
        // And recovery is spent, not sticky: a later seal goes
        // straight through the healthy key, no second regeneration.
        box.encrypt(plain)
        assertEquals(1, source.regenerations)
    }

    @Test
    fun aNonParameterFailureNeverTriggersRegeneration() {
        // An IO-class failure (the keystore itself unreachable)…
        val ioSource = ScriptedKeySource(failure = { IOException("keystore unavailable") })
        val ioBox = AesGcmSecretBox(ioSource::key, ioSource::regenerate)
        try {
            ioBox.encrypt("x".toByteArray(Charsets.UTF_8))
            fail("an IO failure must propagate, not be recovered")
        } catch (e: IOException) {
            // propagated untouched
        }
        assertEquals(0, ioSource.regenerations)

        // …and an invalid-key failure (e.g. the key was invalidated
        // by a lockscreen change) is a different disease too.
        val keySource = ScriptedKeySource(failure = { InvalidKeyException("key invalidated") })
        val keyBox = AesGcmSecretBox(keySource::key, keySource::regenerate)
        try {
            keyBox.encrypt("x".toByteArray(Charsets.UTF_8))
            fail("an invalid-key failure must propagate, not be recovered")
        } catch (e: InvalidKeyException) {
            // propagated untouched
        }
        assertEquals(0, keySource.regenerations)
    }

    @Test
    fun regenerationIsTriedAtMostOnce() {
        var regenerations = 0
        val box = AesGcmSecretBox(
            keyProvider = { throw InvalidAlgorithmParameterException("bad spec") },
            keyRegenerator = {
                regenerations++
                throw InvalidAlgorithmParameterException("still bad")
            },
        )
        try {
            box.encrypt("x".toByteArray(Charsets.UTF_8))
            fail("a second parameter failure must propagate")
        } catch (e: InvalidAlgorithmParameterException) {
            // propagated after exactly one regeneration attempt
        }
        assertEquals(1, regenerations)
    }

    @Test
    fun withoutARegeneratorAParameterFailurePropagates() {
        val box = AesGcmSecretBox(
            keyProvider = { throw InvalidAlgorithmParameterException("bad spec") },
        )
        try {
            box.encrypt("x".toByteArray(Charsets.UTF_8))
            fail("no regenerator wired: the failure is the caller's, as before 0.8.1")
        } catch (e: InvalidAlgorithmParameterException) {
            assertTrue(true)
        }
    }

    @Test
    fun aBlobSealedBeforeRegenerationReadsAsNoSessionNotACrash() {
        // The recorded trade behind scoped regeneration (see the
        // box's doc): the old key's blobs become unreadable, and
        // the decrypt contract already reads that as "no stored
        // session" — null, never a throw — so the stores degrade
        // to a re-sign-in, not a crash or a corrupt-state loop.
        val before = testSecretBox()
        val blob = before.encrypt("session".toByteArray(Charsets.UTF_8))
        val after = testSecretBox()
        assertNull(after.decrypt(blob))
    }
}
