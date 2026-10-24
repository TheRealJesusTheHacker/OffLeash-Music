package dev.cyberdogs.offleash.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session health handling, through the fake transport behind the
 * real client:
 * - healthy: the switcher's enumeration is adopted, so a stored
 *   authUser of 0 is corrected to the identity's real index/pageId;
 * - session dead (401): the credential is destroyed, with the plain
 *   reason recorded for the UI — a dead session is never kept;
 * - unreachable: the session is KEPT and health reads UNKNOWN —
 *   network trouble is not a dead session.
 */
class SessionHealthTest {

    @Test
    fun healthyValidationAdoptsTheEnumeratedAuthUserAndPageId() = runBlocking {
        val store = testSessionStore()
        val seeded = seededStoredSession(authUser = 0, pageId = null)
        store.save(seeded)

        val transport = FakeAccountTransport()
        transport.enumeratedAuthUser = 1
        transport.enumeratedPageId = "page-123"
        val manager = testSessionManager(store, transport)

        val health = manager.validateNow()
        assertEquals(SessionHealth.HEALTHY, health)

        val persisted = store.load()
        assertNotNull(persisted)
        assertEquals(1, persisted!!.authUser)
        assertEquals("page-123", persisted.pageId)
        assertTrue(persisted.lastValidatedEpochMs > seeded.lastValidatedEpochMs)
        assertEquals(seeded.capturedAtEpochMs, persisted.capturedAtEpochMs)
        assertEquals(seeded.cookies, persisted.cookies)

        val snapshot = manager.currentAuthSession()
        assertNotNull(snapshot)
        assertEquals(1, snapshot!!.authUser)
        assertEquals("page-123", snapshot.pageId)
        assertEquals(SessionHealth.HEALTHY, manager.uiState.value.health)
    }

    @Test
    fun needsSignInWipesTheStoreAndRecordsTheReason() = runBlocking {
        val store = testSessionStore()
        store.save(seededStoredSession())

        val transport = FakeAccountTransport(behavior = FakeAccountTransport.Behavior.REJECTED)
        val manager = testSessionManager(store, transport)

        val health = manager.validateNow()
        assertEquals(SessionHealth.NEEDS_SIGN_IN, health)
        assertTrue("a dead session must be destroyed", store.isWipedClean())
        assertNull(store.load())
        assertNull(manager.currentAuthSession())
        assertEquals(false, manager.uiState.value.signedIn)
        assertEquals(
            "Your YouTube session ended. Sign in again to see your library.",
            manager.uiState.value.lastEndReason,
        )
    }

    @Test
    fun unreachableKeepsTheSessionAndReadsUnknown() = runBlocking {
        val store = testSessionStore()
        val seeded = seededStoredSession()
        store.save(seeded)

        val transport = FakeAccountTransport(behavior = FakeAccountTransport.Behavior.THROW)
        val manager = testSessionManager(store, transport)

        val health = manager.validateNow()
        assertEquals(SessionHealth.UNKNOWN, health)
        assertEquals("an unreachable check must not touch the stored session", seeded, store.load())
        assertNotNull(manager.currentAuthSession())
        assertEquals(true, manager.uiState.value.signedIn)
        assertEquals(SessionHealth.UNKNOWN, manager.uiState.value.health)
        assertNull(manager.uiState.value.lastEndReason)
    }
}
