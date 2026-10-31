package dev.cyberdogs.offleash.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round 8 (break the round-7 AutoDownloadLikedWorker credential
 * re-check): the cookie road compared the AuthSession by
 * REFERENCE (`===`), but the session instance was replaced for
 * the SAME account as a matter of routine — every YouTube
 * response could rotate session cookies, and each rotation
 * swapped `snapshot` for a fresh instance, so the worker's
 * reference guard read a healthy same-account session as "no
 * longer current". The fix was a GENERATION that turned over
 * only when the session itself was adopted, ended, or signed
 * out — never on rotation or revalidation.
 *
 * 0.8.3 re-base (the WebView/cookie road is REMOVED — see
 * [SessionManager]): adoption, ending, rotation, and
 * revalidation no longer exist, because no legacy session can
 * exist. What this file still pins is the surviving discipline
 * for the readers built on the pair (the auto-download worker's
 * capture/re-check): the generation is stable across every
 * no-session read, the transport's rotation callback changes
 * nothing, and sign-out — the one turnover left — turns it over.
 */
class R8SessionGenerationTest {

    @Test
    fun rotationCallbackChangesNothingWithoutASession() {
        val store = testSessionStore()
        val manager = testSessionManager(store)
        val generationBefore = manager.sessionGeneration()

        manager.onResponseCookies(mapOf("SID" to "rotated-sid-value"))

        assertNull(
            "no legacy session can be rotated into existence",
            manager.currentAuthSession(),
        )
        assertEquals(
            "the rotation callback is a no-op: the generation stands",
            generationBefore,
            manager.sessionGeneration(),
        )
    }

    @Test
    fun signOutTurnsTheGenerationOver() = runBlocking {
        val store = testSessionStore()
        val manager = testSessionManager(store)
        val before = manager.sessionGeneration()
        manager.signOut()
        assertNotEquals(before, manager.sessionGeneration())
        assertNull(manager.currentAuthSession())
    }
}
