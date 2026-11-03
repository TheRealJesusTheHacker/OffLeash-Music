package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleNativeAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Round 66 pins (adversarial lane, charter 2): the
 * session-presence guard legs at GoogleSessionManager's entry
 * points — carried to the native flow (0.8.8).
 *
 * The device-era legs, and their native heirs:
 *
 *  - [GoogleSessionManager.startSignIn]'s signed-in early
 *    return: without it, a signed-in manager mints a FRESH
 *    sign-in attempt — approve it in the browser and the
 *    standing session is silently replaced by a sign-in that
 *    never signed out. The guard also stands before any
 *    leftover-attempt resume: a leftover persisted beside the
 *    session (written by a crashed or concurrent flow) must
 *    never be republished over the signed-in state.
 *  - restorePendingAttempt's stored-session leg (its `return`
 *    after the wipe): without it, a leftover attempt beside a
 *    stored session is wiped from the store and then STILL
 *    republished from the in-memory copy. Its ordering against
 *    the expiry branch is pinned too: the stored-session leg
 *    runs first, so even an EXPIRED leftover lands no ending
 *    over the signed-in state.
 *  - [GoogleSessionManager.completeSignIn] with nothing
 *    standing (the checkNow leg's native heir): a return
 *    delivered to a signed-in manager — no attempt stands —
 *    exchanges nothing and touches nothing.
 *  - cancelSignIn's phase leg (rounds 67–68): a signed-out
 *    cancel resets ANY non-Idle phase — AwaitingReturn or
 *    Failed alike — to Idle and wipes the attempt; the leg's
 *    condition is the signed-in state, not the phase shape.
 *
 * The device-era rounds 69–73 wire pins (cancel/sign-out
 * landing at every poll-loop interleaving) have no native
 * subject: the native flow has no poll loop — nothing polls,
 * so nothing can poll a cancelled code. Its one network tail,
 * the code exchange inside completeSignIn, carries the same
 * staleness discipline and is pinned where it lives: R7
 * (cancel during the exchange) and R10 (sign-out parked in its
 * wipe against the returning exchange).
 *
 * Each pin below constructs the guarded state through the
 * stores and calls the entry point; each doctor fails exactly
 * its own pin.
 */
class R66SessionGuardPinTest {

    /** Form transport that records every POST; nothing here may reach it. */
    private class CountingFormTransport : HttpTransport {
        val calls = CopyOnWriteArrayList<String>()
        val entered = CountDownLatch(1)

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse = throw UnsupportedOperationException("form POSTs only")

        override fun postForm(
            url: String,
            headers: Map<String, String>,
            formBody: String,
        ): HttpResponse {
            calls.add(formBody)
            entered.countDown()
            return HttpResponse(200, "")
        }
    }

    private class UserinfoTransport : HttpTransport {
        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse = throw UnsupportedOperationException("GET only")

        override fun get(url: String, headers: Map<String, String>): HttpResponse =
            HttpResponse(200, "{}")
    }

    private fun tokenStoreWithSession(): GoogleTokenStore {
        val store = GoogleTokenStore(InMemorySecretStorage(), testSecretBox())
        store.save(
            StoredGoogleSession(
                accessToken = "ya29.standing",
                refreshToken = "rt-standing",
                accessTokenExpiresAtEpochMs = Long.MAX_VALUE,
                identityName = "Standing Listener",
            ),
        )
        return store
    }

    private fun attemptStoreWithLiveAttempt(): GooglePendingNativeAuthStore {
        val store = NativeTestSupport.attemptStore()
        val now = System.currentTimeMillis()
        store.save(
            StoredPendingNativeAuth(
                state = "leftover-state",
                codeVerifier = "leftover-verifier",
                clientId = NativeTestSupport.ANDROID_ID,
                redirectUri = NativeTestSupport.REDIRECT,
                issuedAtEpochMs = now - 60_000L,
                expiresAtEpochMs = now + 1_700_000L,
            ),
        )
        return store
    }

    private fun manager(
        form: CountingFormTransport,
        tokenStore: GoogleTokenStore,
        attemptStore: GooglePendingNativeAuthStore? = null,
    ): GoogleSessionManager = GoogleSessionManager(
        store = tokenStore,
        auth = GoogleNativeAuth(form, NativeTestSupport.ANDROID_ID),
        client = YoutubeiClient(UserinfoTransport()),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        redirectUri = NativeTestSupport.REDIRECT,
        pendingAttemptStore = attemptStore,
        signInDiagnostics = SignInDiagnostics(),
    )

    @Test
    fun startSignInWhileSignedInMintsNoAttemptAndKeepsTheStandingSession() {
        val form = CountingFormTransport()
        val manager = manager(form, tokenStoreWithSession())
        assertTrue(manager.uiState.value.signedIn)
        manager.startSignIn()
        assertNull(
            "startSignIn on a signed-in manager must not mint an " +
                "attempt — the signed-in early return is the only " +
                "thing between the standing session and a fresh " +
                "attempt whose approval would replace it",
            manager.authorizationLaunch.value,
        )
        assertFalse(
            "no wire call may happen either",
            form.entered.await(2, TimeUnit.SECONDS),
        )
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        assertTrue(manager.uiState.value.signedIn)
        assertEquals(
            "Standing Listener",
            manager.uiState.value.identityName,
        )
    }

    @Test
    fun startSignInWhileSignedInDoesNotResumeALeftoverAttemptEither() {
        val form = CountingFormTransport()
        val attemptStore = NativeTestSupport.attemptStore()
        val manager = manager(form, tokenStoreWithSession(), attemptStore)
        assertTrue(manager.uiState.value.signedIn)
        // The leftover state, written AFTER construction (a
        // crashed or concurrent flow's record — at construction
        // it would have been wiped as stale): startSignIn's
        // signed-in guard must stand before any resume, or the
        // leftover is republished behind the standing session.
        val now = System.currentTimeMillis()
        attemptStore.save(
            StoredPendingNativeAuth(
                state = "leftover-state",
                codeVerifier = "leftover-verifier",
                clientId = NativeTestSupport.ANDROID_ID,
                redirectUri = NativeTestSupport.REDIRECT,
                issuedAtEpochMs = now - 60_000L,
                expiresAtEpochMs = now + 1_700_000L,
            ),
        )
        manager.startSignIn()
        assertEquals(
            "startSignIn on a signed-in manager must not " +
                "republish a leftover attempt over the signed-in state",
            GoogleSignInPhase.Idle,
            manager.phase.value,
        )
        assertNull(manager.authorizationLaunch.value)
        assertFalse(
            "no wire call may happen for the leftover either",
            form.entered.await(1, TimeUnit.SECONDS),
        )
        assertTrue(manager.uiState.value.signedIn)
        assertEquals(
            "Standing Listener",
            manager.uiState.value.identityName,
        )
    }

    @Test
    fun completeSignInWhileSignedInExchangesNothing() {
        val form = CountingFormTransport()
        val manager = manager(form, tokenStoreWithSession())
        assertTrue(manager.uiState.value.signedIn)
        // A return delivered with no attempt standing (the
        // native heir of the checkNow leg): nothing is standing
        // to collect it against, so nothing is exchanged and the
        // standing session is untouched.
        manager.completeSignIn(
            NativeTestSupport.codeRedirect(state = "no-standing-attempt")
        )
        assertFalse(
            "no exchange may run for a return with no standing attempt",
            form.entered.await(1, TimeUnit.SECONDS),
        )
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        assertTrue(manager.uiState.value.signedIn)
        assertEquals(
            "Standing Listener",
            manager.uiState.value.identityName,
        )
    }

    @Test
    fun aLeftoverAttemptBesideAStoredSessionIsWipedAtRestoreAndNeverPublished() {
        val form = CountingFormTransport()
        val attemptStore = attemptStoreWithLiveAttempt()
        val manager = manager(form, tokenStoreWithSession(), attemptStore)
        assertTrue(manager.uiState.value.signedIn)
        assertNull(
            "a leftover attempt beside a stored session is stale " +
                "and wiped at restore, not resumed",
            attemptStore.load(),
        )
        assertEquals(
            "the wiped attempt must not be republished from its " +
                "in-memory copy either — restore's stored-session " +
                "leg returns right after the wipe",
            GoogleSignInPhase.Idle,
            manager.phase.value,
        )
        assertNull(manager.authorizationLaunch.value)
        assertFalse(
            "no wire call may run for the wiped attempt",
            form.entered.await(1, TimeUnit.SECONDS),
        )
    }

    @Test
    fun anExpiredLeftoverAttemptBesideAStoredSessionIsWipedAtRestoreWithoutAFailureCard() {
        val form = CountingFormTransport()
        val attemptStore = NativeTestSupport.attemptStore()
        val now = System.currentTimeMillis()
        attemptStore.save(
            StoredPendingNativeAuth(
                state = "stale-state",
                codeVerifier = "stale-verifier",
                clientId = NativeTestSupport.ANDROID_ID,
                redirectUri = NativeTestSupport.REDIRECT,
                issuedAtEpochMs = now - 3_600_000L,
                expiresAtEpochMs = now - 60_000L,
            ),
        )
        val manager = manager(form, tokenStoreWithSession(), attemptStore)
        assertTrue(manager.uiState.value.signedIn)
        assertNull(
            "an expired leftover attempt beside a stored session " +
                "is stale and wiped at restore",
            attemptStore.load(),
        )
        assertEquals(
            "no ending may land either: restore's stored-session " +
                "leg runs before the expiry branch, so no phase is " +
                "published over the signed-in state",
            GoogleSignInPhase.Idle,
            manager.phase.value,
        )
        assertNull(manager.authorizationLaunch.value)
        assertFalse(
            "no wire call may run for the wiped attempt",
            form.entered.await(1, TimeUnit.SECONDS),
        )
    }

    @Test
    fun cancelSignInWhileSignedOutResetsThePhaseAndWipesTheAttempt() {
        val form = CountingFormTransport()
        val attemptStore = attemptStoreWithLiveAttempt()
        val tokenStore = GoogleTokenStore(
            InMemorySecretStorage(),
            testSecretBox(),
        )
        val manager = manager(form, tokenStore, attemptStore)
        assertFalse(manager.uiState.value.signedIn)
        assertTrue(
            "the live attempt is resumed at restore, so the " +
                "phase stands at AwaitingReturn before the cancel",
            manager.phase.value is GoogleSignInPhase.AwaitingReturn,
        )
        manager.cancelSignIn()
        assertEquals(
            "a signed-out cancel ends the attempt: the phase " +
                "returns to Idle",
            GoogleSignInPhase.Idle,
            manager.phase.value,
        )
        assertNull(manager.authorizationLaunch.value)
        assertNull(
            "a cancelled attempt is wiped, never resumed later",
            attemptStore.load(),
        )
    }

    @Test
    fun cancelSignInAfterAFailureAlsoResetsThePhaseToIdle() = runBlocking {
        // The round-68 neighbour: the cancel leg's condition is
        // the signed-in state, not the phase shape — a signed-out
        // cancel after a FAILED attempt (here, a declined return)
        // must reset the phase too, or the failure card stands
        // forever.
        val form = CountingFormTransport()
        val attemptStore = NativeTestSupport.attemptStore()
        val tokenStore = GoogleTokenStore(
            InMemorySecretStorage(),
            testSecretBox(),
        )
        val manager = manager(form, tokenStore, attemptStore)
        manager.startSignIn()
        val launch = manager.authorizationLaunch.value!!
        manager.completeSignIn(
            NativeTestSupport.deniedRedirect(
                NativeTestSupport.stateFrom(launch.url)
            )
        )
        assertTrue(
            "the declined return lands on the Failed phase " +
                "before the cancel",
            manager.phase.value is GoogleSignInPhase.Failed,
        )
        manager.cancelSignIn()
        assertEquals(
            "a signed-out cancel ends a failed attempt too: the " +
                "phase returns to Idle whatever non-Idle phase " +
                "the attempt ended on",
            GoogleSignInPhase.Idle,
            manager.phase.value,
        )
        assertNull(
            "the attempt stays wiped after the cancel",
            attemptStore.load(),
        )
    }
}
