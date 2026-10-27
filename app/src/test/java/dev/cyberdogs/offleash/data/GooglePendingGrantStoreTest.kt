package dev.cyberdogs.offleash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pending device-grant store (0.7.1): the custody contract for
 * an issued-but-unapproved sign-in code — round-trip fidelity, NO
 * plaintext grant material in the stored bytes (the device code is
 * credential-adjacent: it collects the tokens), verified wipe,
 * corruption tolerance — over its own sealed file shape, mirroring
 * [GoogleTokenStoreTest].
 */
class GooglePendingGrantStoreTest {

    private fun testPendingStore(
        storage: SecretStorage = InMemorySecretStorage(),
    ): GooglePendingGrantStore = GooglePendingGrantStore(storage, testSecretBox())

    private fun sampleGrant(): StoredPendingGrant = StoredPendingGrant(
        deviceCode = "PLAIN_DEVICE_CODE_9f31c2",
        userCode = "WDJB-MJHT",
        verificationUrl = "https://www.google.com/device",
        issuedAtEpochMs = 1_700_000_000_000L,
        expiresAtEpochMs = 1_700_001_800_000L,
        intervalSeconds = 5L,
    )

    @Test
    fun saveThenLoadReturnsTheSameGrant() {
        val store = testPendingStore()
        val grant = sampleGrant()
        store.save(grant)
        assertEquals(grant, store.load())
        assertFalse(store.isWipedClean())
    }

    @Test
    fun storedBytesContainNoPlaintextGrantMaterial() {
        val storage = InMemorySecretStorage()
        val store = testPendingStore(storage)
        store.save(sampleGrant())

        val rawBytes = storage.read()
        assertTrue("storage must hold ciphertext after save", rawBytes != null)
        val asText = String(rawBytes!!, Charsets.ISO_8859_1)
        for (secret in listOf("PLAIN_DEVICE_CODE_9f31c2", "WDJB-MJHT")) {
            assertFalse(
                "stored bytes must not contain plaintext: $secret",
                asText.contains(secret),
            )
        }
    }

    @Test
    fun loadAfterWipeReturnsNullAndWipeVerifies() {
        val store = testPendingStore()
        store.save(sampleGrant())
        assertTrue(store.wipe())
        assertNull(store.load())
        assertTrue(store.isWipedClean())
    }

    @Test
    fun corruptBytesReadAsNoGrant() {
        val storage = InMemorySecretStorage()
        val store = testPendingStore(storage)
        storage.write(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertNull(store.load())
    }

    @Test
    fun emptyStorageReadsAsNoGrant() {
        assertNull(testPendingStore().load())
    }

    @Test
    fun livenessIsJudgedAgainstTheExpiryInstant() {
        val grant = sampleGrant()
        assertTrue(grant.isLiveAt(grant.issuedAtEpochMs))
        assertTrue(grant.isLiveAt(grant.expiresAtEpochMs - 1))
        assertFalse(grant.isLiveAt(grant.expiresAtEpochMs))
        assertFalse(grant.isLiveAt(grant.expiresAtEpochMs + 1))
    }
}
