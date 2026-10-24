package dev.cyberdogs.offleash.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
