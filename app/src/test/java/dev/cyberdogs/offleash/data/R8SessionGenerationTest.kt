package dev.cyberdogs.offleash.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 8 (break the round-7 AutoDownloadLikedWorker credential
 * re-check): the cookie road compared the AuthSession by
 * REFERENCE (`===`), but the session instance is replaced for the
 * SAME account as a matter of routine — every YouTube response
 * can rotate session cookies (OkHttpTransport hands them to
 * [SessionManager.onResponseCookies]; the rotating names include
 * the short-lived SIDCC / PSIDTS family), and each rotation swaps
 * `snapshot` for a fresh instance. The liked-list fetch rides
 * that very transport, so its own response could rotate the
 * session before the re-check ran: the guard then read a healthy
 * same-account session as "no longer current" and the worker
 * silently never enqueued — potentially on every run. The
 * credential's identity is a GENERATION that turns over only when
 * the session itself is adopted, ended, or signed out — never on
 * rotation or revalidation of the same session (the
 * LikeController sessionGeneration discipline, round 6).
 */
class R8SessionGenerationTest {

    @Test
    fun cookieRotationReplacesTheInstanceButNotTheGeneration() {
        val store = testSessionStore()
        store.save(seededStoredSession(authUser = 0, pageId = null))
        val manager = testSessionManager(store, FakeAccountTransport())
        val before = manager.currentAuthSession()
        val generationBefore = manager.sessionGeneration()

        manager.onResponseCookies(mapOf("SID" to "rotated-sid-value"))

        val deadline = System.currentTimeMillis() + 10_000
        var after = manager.currentAuthSession()
        while (after === before && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            after = manager.currentAuthSession()
        }
        assertNotSame(
            "rotation replaces the AuthSession instance — the round-7 " +
                "reference guard could not survive its own fetch's response",
            before,
            after,
        )
        assertEquals("rotated-sid-value", after?.cookies?.get("SID"))
        assertEquals(before?.authUser, after?.authUser)
        assertEquals(
            "the same account's session, rotated, is the same generation",
            generationBefore,
            manager.sessionGeneration(),
        )
    }

    @Test
    fun revalidationOfTheSameSessionKeepsTheGeneration() = runBlocking {
        val store = testSessionStore()
        store.save(seededStoredSession(authUser = 0, pageId = null))
        val manager = testSessionManager(store, FakeAccountTransport())
        val before = manager.sessionGeneration()
        assertEquals(SessionHealth.HEALTHY, manager.validateNow())
        assertEquals(before, manager.sessionGeneration())
    }

    @Test
    fun sessionEndAndSignOutAndAdoptionEachTurnTheGenerationOver() = runBlocking {
        // End (YouTube says the session is dead):
        val endStore = testSessionStore()
        endStore.save(seededStoredSession())
        val endManager = testSessionManager(
            endStore,
            FakeAccountTransport(FakeAccountTransport.Behavior.REJECTED),
        )
        val endBefore = endManager.sessionGeneration()
        assertEquals(SessionHealth.NEEDS_SIGN_IN, endManager.validateNow())
        assertNull(endManager.currentAuthSession())
        assertNotEquals(endBefore, endManager.sessionGeneration())

        // Sign-out:
        val outStore = testSessionStore()
        outStore.save(seededStoredSession())
        val outManager = testSessionManager(outStore, FakeAccountTransport())
        val outBefore = outManager.sessionGeneration()
        outManager.signOut()
        assertNotEquals(outBefore, outManager.sessionGeneration())

        // Adoption of a (new) sign-in:
        val adoptStore = testSessionStore()
        val adoptManager = testSessionManager(adoptStore, FakeAccountTransport())
        val adoptBefore = adoptManager.sessionGeneration()
        val attempt = adoptManager.completeSignIn(FakeAccountTransport.RAW_COOKIE)
        assertTrue(attempt is SignInAttempt.Success)
        assertNotEquals(adoptBefore, adoptManager.sessionGeneration())
    }
}
