package dev.cyberdogs.offleash.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round 9: the session snapshot and its generation must be
 * readable as ONE consistent pair. The AutoDownloadLikedWorker's
 * round-8 capture read the session and then the generation as
 * two separate reads — a sign-out + sign-in landing between them
 * paired the OLD account's session with the NEW generation, and
 * the worker's generation re-check then passed for a liked list
 * fetched under a session that was no longer current.
 * [SessionManager.currentSessionWithGeneration] brackets the
 * snapshot read with generation reads that must agree.
 *
 * 0.8.3 re-base (the WebView/cookie road is REMOVED — see
 * [SessionManager]): no legacy session can stand any more — a
 * stored one is discarded at construction — so the pair's
 * session is always null. The consistency discipline still
 * matters for the worker's re-check: the pair's generation is
 * the standing generation at every read, and sign-out turns the
 * pair over atomically (new generation, still no session).
 */
class R9SessionPairingTest {

    @Test
    fun pairCarriesNoSessionAndTheStandingGeneration() {
        val store = testSessionStore()
        store.save(seededStoredSession(authUser = 0, pageId = null))
        val manager = testSessionManager(store)
        val (session, generation) = manager.currentSessionWithGeneration()
        assertNull(
            "the seeded legacy session is discarded at construction — " +
                "the pair can never carry it",
            session,
        )
        assertEquals(
            "the pair's generation is the standing generation",
            manager.sessionGeneration(),
            generation,
        )
    }

    @Test
    fun pairTurnsOverWithSignOut() = runBlocking {
        val store = testSessionStore()
        val manager = testSessionManager(store)
        val before = manager.currentSessionWithGeneration()
        manager.signOut()
        val after = manager.currentSessionWithGeneration()
        assertNull("after sign-out the pair carries no session", after.first)
        assertNotEquals(
            "after sign-out the pair carries the turned-over generation, " +
                "never the pre-sign-out generation",
            before.second,
            after.second,
        )
        assertEquals(manager.sessionGeneration(), after.second)
    }
}
