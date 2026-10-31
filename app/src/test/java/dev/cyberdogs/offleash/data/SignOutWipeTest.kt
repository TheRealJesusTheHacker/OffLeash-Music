package dev.cyberdogs.offleash.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sign-out is a verified wipe (SimpMusic's sign-out left its
 * plaintext cookie copies behind — this suite pins that ours
 * does not).
 *
 * 0.8.3 re-base (the WebView/cookie road is REMOVED — see
 * [SessionManager]): the old legs drove a real sign-in through
 * the fake transport and then signed out, and pinned rollback
 * for failed attempts. Sign-in attempts no longer exist, so
 * what remains pinnable is the wipe itself: bytes placed in the
 * legacy store (as an older build could have left them) are
 * gone after sign-out, and the presented state is signed out.
 * The construction-time discard of a pre-existing stored
 * session is pinned in LegacyRoadRemovedTest.
 */
class SignOutWipeTest {

    @Test
    fun signOutLeavesNothingBehind() = runBlocking {
        val storage = InMemorySecretStorage()
        val store = testSessionStore(storage)
        val manager = testSessionManager(store)

        // Bytes in the legacy store, as an older build or a
        // half-finished state could have left them (seeded AFTER
        // construction — construction's own discard is the other
        // test file's pin).
        store.save(seededStoredSession())
        assertFalse("store must be non-empty before sign-out", store.isWipedClean())

        manager.signOut()

        assertTrue("sign-out must leave the store wiped clean", store.isWipedClean())
        assertNull(store.load())
        assertNull(manager.currentAuthSession())
        assertFalse(manager.uiState.value.signedIn)
        assertNull(manager.uiState.value.lastEndReason)
    }
}
