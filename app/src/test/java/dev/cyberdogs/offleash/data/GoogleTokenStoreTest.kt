package dev.cyberdogs.offleash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Google token store (0.7.0): the same custody contract as the
 * cookie session's store — round-trip fidelity, NO plaintext token
 * or identity in the stored bytes, verified wipe, corruption
 * tolerance — over its own sealed file shape.
 */
class GoogleTokenStoreTest {

    private fun testGoogleStore(
        storage: SecretStorage = InMemorySecretStorage(),
    ): GoogleTokenStore = GoogleTokenStore(storage, testSecretBox())

    private fun sampleTokens(): StoredGoogleSession = StoredGoogleSession(
        accessToken = "PLAIN_ACCESS_TOKEN_6d1a90",
        refreshToken = "PLAIN_REFRESH_TOKEN_2b77e4",
        accessTokenExpiresAtEpochMs = 1_700_003_600_000L,
        grantedScopes = "https://www.googleapis.com/auth/youtube openid profile email",
        identityName = "Test Listener",
        identityEmail = "listener@example.com",
        avatarUrl = "https://example.com/avatar.png",
        grantedAtEpochMs = 1_700_000_000_000L,
        lastValidatedEpochMs = 1_700_000_100_000L,
    )

    @Test
    fun saveThenLoadReturnsTheSameTokens() {
        val store = testGoogleStore()
        val tokens = sampleTokens()
        store.save(tokens)
        assertEquals(tokens, store.load())
        assertFalse(store.isWipedClean())
    }

    @Test
    fun storedBytesContainNoPlaintextSecrets() {
        val storage = InMemorySecretStorage()
        val store = testGoogleStore(storage)
        store.save(sampleTokens())

        val rawBytes = storage.read()
        assertTrue("storage must hold ciphertext after save", rawBytes != null)
        val asText = String(rawBytes!!, Charsets.ISO_8859_1)
        for (secret in listOf(
            "PLAIN_ACCESS_TOKEN_6d1a90",
            "PLAIN_REFRESH_TOKEN_2b77e4",
            "listener@example.com",
            "Test Listener",
        )) {
            assertFalse(
                "stored bytes must not contain plaintext: $secret",
                asText.contains(secret),
            )
        }
    }

    @Test
    fun loadAfterWipeReturnsNullAndWipeVerifies() {
        val store = testGoogleStore()
        store.save(sampleTokens())
        assertTrue(store.wipe())
        assertNull(store.load())
        assertTrue(store.isWipedClean())
    }

    @Test
    fun corruptBytesReadAsNoSession() {
        val storage = InMemorySecretStorage()
        val store = testGoogleStore(storage)
        storage.write(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertNull(store.load())
    }

    @Test
    fun emptyStorageReadsAsNoSession() {
        assertNull(testGoogleStore().load())
    }
}
