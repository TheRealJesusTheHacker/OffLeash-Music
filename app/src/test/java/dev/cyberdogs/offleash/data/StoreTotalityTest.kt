package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleDeviceAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * H3 totality at the store layer (0.7.2 sweep): the 0.6.4 crash was
 * a throw escaping the first-ever Keystore session write, and the
 * corridor built then wrapped the managers' save sites. This suite
 * closes the layer UNDERNEATH: for every sealed store, a storage
 * layer that throws on every operation must read as "no session"
 * on load, report an unverified wipe as false, and never throw out
 * of load / wipe / isWipedClean into a manager's init or a
 * sign-out. (Save keeps its throw — its callers must know, because
 * a silently failed save would leave the UI claiming a sign-in
 * nothing persisted; those call sites are pinned elsewhere.)
 */
class StoreTotalityTest {

    /** A storage layer in total failure: every operation throws. */
    private class ThrowingStorage : SecretStorage {
        override fun read(): ByteArray? =
            throw RuntimeException("simulated storage read failure")

        override fun write(bytes: ByteArray) =
            throw RuntimeException("simulated storage write failure")

        override fun clear() =
            throw RuntimeException("simulated storage clear failure")
    }

    @Test
    fun googleTokenStoreReadsAFailingStorageAsNoSession() {
        val store = GoogleTokenStore(ThrowingStorage(), testSecretBox())
        assertNull(store.load())
        assertFalse("an unverifiable wipe reports false", store.wipe())
        assertFalse(store.isWipedClean())
    }

    @Test
    fun googlePendingGrantStoreReadsAFailingStorageAsNoGrant() {
        val store = GooglePendingGrantStore(ThrowingStorage(), testSecretBox())
        assertNull(store.load())
        assertFalse(store.wipe())
        assertFalse(store.isWipedClean())
    }

    @Test
    fun sessionStoreReadsAFailingStorageAsNoSession() {
        val store = SessionStore(ThrowingStorage(), testSecretBox())
        assertNull(store.load())
        assertFalse(store.wipe())
        assertFalse(store.isWipedClean())
    }

    @Test
    fun corruptSealedJsonReadsAsNoSessionInEveryStore() {
        // Bytes that decrypt fine but are not the DTO's JSON: a
        // changed shape from a future/past build must read as
        // signed out, never crash (the decode-totality half of H3).
        val box = testSecretBox()
        val sealedGarbage = box.encrypt("this is not the stored shape".toByteArray())

        val googleStorage = InMemorySecretStorage().apply { write(sealedGarbage) }
        assertNull(GoogleTokenStore(googleStorage, box).load())

        val pendingStorage = InMemorySecretStorage().apply { write(sealedGarbage) }
        assertNull(GooglePendingGrantStore(pendingStorage, box).load())

        val sessionStorage = InMemorySecretStorage().apply { write(sealedGarbage) }
        assertNull(SessionStore(sessionStorage, box).load())
    }

    @Test
    fun managersConstructSignedOutOverAFailingStore() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val transport = FakeAccountTransport()
        val client = YoutubeiClient(transport)

        val sessionManager = SessionManager(
            store = SessionStore(ThrowingStorage(), testSecretBox()),
            client = client,
            scope = scope,
            signInDiagnostics = SignInDiagnostics(),
        )
        assertFalse(sessionManager.uiState.value.signedIn)
        assertNull(sessionManager.currentAuthSession())

        val googleManager = GoogleSessionManager(
            store = GoogleTokenStore(ThrowingStorage(), testSecretBox()),
            auth = GoogleDeviceAuth(transport),
            client = client,
            scope = scope,
            pendingGrantStore = GooglePendingGrantStore(ThrowingStorage(), testSecretBox()),
            signInDiagnostics = SignInDiagnostics(),
        )
        assertFalse(googleManager.uiState.value.signedIn)
        assertFalse(googleManager.hasStoredSession())
    }
}
