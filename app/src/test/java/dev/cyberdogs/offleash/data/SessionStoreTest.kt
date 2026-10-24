package dev.cyberdogs.offleash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The encrypted session store: round-trip fidelity, the
 * no-plaintext-on-disk assertion at the heart of the Phase 2
 * security pass (SimpMusic kept the cookie in plaintext three
 * times over — here the bytes in storage must contain neither the
 * cookie value nor the identity), wipe verification, and
 * corruption tolerance.
 */
class SessionStoreTest {

    private fun sampleSession(): StoredSession = StoredSession(
        cookies = mapOf(
            "SAPISID" to "PLAIN_COOKIE_VALUE_9f27c1",
            "__Secure-3PAPISID" to "PLAIN_3P_VALUE_41bd88",
            "SID" to "PLAIN_SID_VALUE_07e5aa",
        ),
        authUser = 1,
        pageId = "page-123",
        visitorData = "VISITOR_VALUE_55c2",
        identityName = "Test Listener",
        identityEmail = "listener@example.com",
        avatarUrl = "https://example.com/avatar.png",
        capturedAtEpochMs = 1_700_000_000_000L,
        lastValidatedEpochMs = 1_700_000_100_000L,
    )

    @Test
    fun saveThenLoadReturnsTheSameSession() {
        val store = testSessionStore()
        val session = sampleSession()
        store.save(session)
        assertEquals(session, store.load())
        assertFalse(store.isWipedClean())
    }

    @Test
    fun storedBytesContainNoPlaintextSecrets() {
        val storage = InMemorySecretStorage()
        val store = testSessionStore(storage)
        val session = sampleSession()
        store.save(session)

        val rawBytes = storage.read()
        assertTrue("storage must hold ciphertext after save", rawBytes != null)
        // ISO-8859-1 maps bytes 1:1, so a plaintext substring in the
        // bytes would survive this decoding and be caught.
        val asText = String(rawBytes!!, Charsets.ISO_8859_1)
        for (secret in listOf(
            "PLAIN_COOKIE_VALUE_9f27c1",
            "PLAIN_3P_VALUE_41bd88",
            "PLAIN_SID_VALUE_07e5aa",
            "listener@example.com",
            "Test Listener",
        )) {
            assertFalse("stored bytes must not contain plaintext: $secret", asText.contains(secret))
        }
    }

    @Test
    fun loadAfterWipeReturnsNullAndWipeVerifies() {
        val store = testSessionStore()
        store.save(sampleSession())
        assertTrue("wipe must verify the store is empty", store.wipe())
        assertTrue(store.isWipedClean())
        assertNull(store.load())
    }

    @Test
    fun corruptBytesLoadAsNoSession() {
        val storage = InMemorySecretStorage()
        val store = testSessionStore(storage)
        storage.write(byteArrayOf(1, 2, 3, 4, 5))
        assertNull(store.load())
        // Random bytes of a plausible length still fail the GCM tag.
        val noise = ByteArray(128) { (it * 37 + 11).toByte() }
        storage.write(noise)
        assertNull(store.load())
    }

    @Test
    fun fileStorageClearOverwritesAndDeletes() {
        val dir = File(System.getProperty("java.io.tmpdir"), "offleash-store-test-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        val file = File(dir, "session.bin")
        try {
            val storage = FileSecretStorage(file)
            val store = SessionStore(storage, testSecretBox())
            store.save(sampleSession())
            assertTrue(file.isFile)
            assertEquals(sampleSession(), store.load())

            assertTrue(store.wipe())
            assertFalse("clear() must delete the session file", file.exists())
            assertNull(storage.read())
            assertTrue(store.isWipedClean())
        } finally {
            file.delete()
            dir.delete()
        }
    }
}
