package dev.cyberdogs.offleash.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sign-out is a verified wipe (SimpMusic's sign-out left its
 * plaintext cookie copies behind — this suite pins that ours does
 * not), and a failed sign-in attempt rolls back by construction:
 * the previously stored session survives byte-for-byte.
 *
 * Driven through a fake [FakeAccountTransport] behind the real
 * client, per the Phase 2 test plan.
 */
class SignOutWipeTest {

    @Test
    fun signInThenSignOutLeavesNothingBehind() = runBlocking {
        val storage = InMemorySecretStorage()
        val store = testSessionStore(storage)
        val transport = FakeAccountTransport()
        val manager = testSessionManager(store, transport)

        val result = manager.completeSignIn(FakeAccountTransport.RAW_COOKIE)
        assertTrue("expected Success, was $result", result is SignInAttempt.Success)
        assertEquals(
            FakeAccountTransport.IDENTITY_NAME,
            (result as SignInAttempt.Success).identityName,
        )
        assertFalse("store must be non-empty while signed in", store.isWipedClean())
        assertNotNull(store.load())
        assertNotNull(manager.currentAuthSession())
        assertTrue(manager.uiState.value.signedIn)

        manager.signOut()

        assertTrue("sign-out must leave the store wiped clean", store.isWipedClean())
        assertNull(store.load())
        assertNull(manager.currentAuthSession())
        assertFalse(manager.uiState.value.signedIn)
        assertNull(manager.uiState.value.lastEndReason)
    }

    @Test
    fun rejectedSignInLeavesThePreviousSessionExactlyAsItWas() = runBlocking {
        val storage = InMemorySecretStorage()
        val store = testSessionStore(storage)
        val seeded = seededStoredSession()
        store.save(seeded)

        val transport = FakeAccountTransport(behavior = FakeAccountTransport.Behavior.REJECTED)
        val manager = testSessionManager(store, transport)
        assertNotNull(manager.currentAuthSession())

        val result = manager.completeSignIn(FakeAccountTransport.RAW_COOKIE)
        assertTrue("expected Failed, was $result", result is SignInAttempt.Failed)

        // Rollback by construction: nothing was persisted before the
        // identity answer, so the old session loads identically.
        assertEquals(seeded, store.load())
        assertEquals(seeded.cookies, manager.currentAuthSession()?.cookies)
        assertEquals(seeded.authUser, manager.currentAuthSession()?.authUser)
        assertTrue(manager.uiState.value.signedIn)
        assertEquals(seeded.identityEmail, manager.uiState.value.identityEmail)
    }

    @Test
    fun implausibleCookieIsRefusedWithoutStoringAnything() = runBlocking {
        val storage = InMemorySecretStorage()
        val store = testSessionStore(storage)
        val transport = FakeAccountTransport()
        val manager = testSessionManager(store, transport)

        val result = manager.completeSignIn("CONSENT=YES+; PREF=f1=50000000")
        assertTrue("expected Failed, was $result", result is SignInAttempt.Failed)
        assertTrue(
            (result as SignInAttempt.Failed).reason.startsWith(
                "That sign-in didn't include a usable YouTube session",
            ),
        )
        assertTrue(store.isWipedClean())
        assertNull(manager.currentAuthSession())
        assertFalse(manager.uiState.value.signedIn)
    }
}
