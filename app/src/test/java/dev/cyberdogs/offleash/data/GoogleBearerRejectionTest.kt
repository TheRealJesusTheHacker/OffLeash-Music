package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleNativeAuth
import dev.cyberdogs.offleash.core.auth.GoogleOAuth
import dev.cyberdogs.offleash.core.auth.IdentityLookup
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The sign-in perfection pass's session-life suite: the reactive
 * 401 → refresh → retry-once path the sweep's W2 lane left open,
 * and the two honesty endings it depends on.
 *
 * Until this pass, token freshness was proactive only (refresh
 * inside the 60-second skew). A token Google refused OUTSIDE that
 * window — a grant revoked between refreshes, or a device clock
 * running behind so the token read locally valid past its real
 * expiry — failed every surface call with a raw 401 while the UI
 * kept claiming "signed in". These tests pin the closed loop:
 * the manager's [GoogleSessionManager.bearerAfterRejection], the
 * [BearerRetryTransport] composition with the real core client,
 * the terminal `invalid_grant` poll ending, and the expired
 * ending's freedom from a hardcoded code lifetime.
 */
class GoogleBearerRejectionTest {

    /**
     * Form-POST transport routed like Google's endpoints: code
     * exchanges are counted and answered by [onExchange];
     * refreshes are counted and answered by [onRefresh].
     */
    private class RoutingFormTransport : HttpTransport {
        val exchangeRequests = AtomicInteger(0)
        val refreshRequests = AtomicInteger(0)

        @Volatile
        var onExchange: () -> HttpResponse = {
            HttpResponse(200, GRANT_BODY)
        }

        @Volatile
        var onRefresh: () -> HttpResponse = {
            HttpResponse(200, REFRESHED_BODY)
        }

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse = throw UnsupportedOperationException("form POSTs only")

        override fun postForm(
            url: String,
            headers: Map<String, String>,
            formBody: String,
        ): HttpResponse = when {
            url == GoogleOAuth.TOKEN_URL &&
                formBody.contains("grant_type=authorization_code") -> {
                exchangeRequests.incrementAndGet()
                onExchange()
            }
            url == GoogleOAuth.TOKEN_URL && formBody.contains("grant_type=refresh_token") -> {
                refreshRequests.incrementAndGet()
                onRefresh()
            }
            else -> throw AssertionError("unexpected form POST: $url body=$formBody")
        }
    }

    /** A client transport that must never be used by these tests. */
    private class UnusedTransport : HttpTransport {
        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse = throw AssertionError("client unused in this test")

        override fun get(url: String, headers: Map<String, String>): HttpResponse =
            throw AssertionError("client unused in this test")
    }

    private fun testTokenStore(): GoogleTokenStore =
        GoogleTokenStore(InMemorySecretStorage(), testSecretBox())

    private fun testPendingStore(): GooglePendingNativeAuthStore =
        GooglePendingNativeAuthStore(InMemorySecretStorage(), testSecretBox())

    /**
     * A manager over a store primed with the stale-but-locally-
     * valid session: its access token has an hour of LOCAL life
     * left, so the proactive path would never refresh it — only
     * the reactive path can save a call Google refuses.
     */
    private fun primedManager(
        form: RoutingFormTransport,
        tokenStore: GoogleTokenStore,
        pendingStore: GooglePendingNativeAuthStore? = null,
        refreshToken: String? = "rt-1",
    ): GoogleSessionManager {
        tokenStore.save(
            StoredGoogleSession(
                accessToken = STALE_TOKEN,
                refreshToken = refreshToken,
                accessTokenExpiresAtEpochMs = NOW + 3_600_000L,
                identityName = "Test Listener",
                identityEmail = "listener@example.com",
                grantedAtEpochMs = NOW,
                lastValidatedEpochMs = NOW,
            )
        )
        return GoogleSessionManager(
            store = tokenStore,
            auth = GoogleNativeAuth(form, NativeTestSupport.ANDROID_ID),
            client = YoutubeiClient(UnusedTransport()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            redirectUri = NativeTestSupport.REDIRECT,
            pendingAttemptStore = pendingStore,
            signInDiagnostics = SignInDiagnostics(),
            nowMs = { NOW },
        )
    }

    // ------------------------------------------------------------------
    // bearerAfterRejection
    // ------------------------------------------------------------------

    @Test
    fun aRefusedStandingTokenIsForceRefreshedAndTheFreshTokenReturned() {
        val form = RoutingFormTransport()
        val store = testTokenStore()
        val manager = primedManager(form, store)

        val retry = manager.bearerAfterRejection(STALE_TOKEN)

        assertEquals(FRESH_TOKEN, retry)
        assertEquals(1, form.refreshRequests.get())
        // The rotation is persisted, and the session stands.
        assertEquals(FRESH_TOKEN, store.load()?.accessToken)
        assertTrue(manager.uiState.value.signedIn)
        // And the proactive path now serves the fresh token
        // without touching the network again.
        assertEquals(FRESH_TOKEN, manager.currentBearer())
        assertEquals(1, form.refreshRequests.get())
    }

    @Test
    fun theTokenJustRotatedAwayFromAnswersWithTheStandingToken() {
        val form = RoutingFormTransport()
        val manager = primedManager(form, testTokenStore())
        // One rotation happens (a refusal of the stale token).
        assertEquals(FRESH_TOKEN, manager.bearerAfterRejection(STALE_TOKEN))
        assertEquals(1, form.refreshRequests.get())

        // A slower request that left with the stale token is
        // refused only now: its retry is the standing token, with
        // NO second refresh spent on it.
        assertEquals(FRESH_TOKEN, manager.bearerAfterRejection(STALE_TOKEN))
        assertEquals(1, form.refreshRequests.get())
    }

    @Test
    fun aRevokedGrantOnTheForcedRefreshEndsTheSessionHonestly() {
        val form = RoutingFormTransport()
        form.onRefresh = {
            HttpResponse(400, """{"error":"invalid_grant"}""")
        }
        val store = testTokenStore()
        val manager = primedManager(form, store)

        assertNull(manager.bearerAfterRejection(STALE_TOKEN))

        assertNull(store.load())
        val state = manager.uiState.value
        assertFalse(state.signedIn)
        assertEquals(SessionHealth.NEEDS_SIGN_IN, state.health)
        assertTrue(state.lastEndReason?.contains("revoked") == true)
    }

    @Test
    fun aFailedForcedRefreshKeepsTheSessionAndIsSpentOnlyOnce() {
        val form = RoutingFormTransport()
        form.onRefresh = {
            HttpResponse(503, """{"error":"temporarily_unavailable"}""")
        }
        val store = testTokenStore()
        val manager = primedManager(form, store)

        assertNull(manager.bearerAfterRejection(STALE_TOKEN))
        // Offline is not a revocation: the session stands.
        assertEquals(STALE_TOKEN, store.load()?.accessToken)
        assertTrue(manager.uiState.value.signedIn)

        // A second refusal of the SAME token (another surface
        // replaying it) must not spend a second token-endpoint
        // call while the failure is fresh — one forced refresh
        // per token per cooldown (0.8.7 sweep 1: this suite's
        // clock is frozen, so the cooldown never lapses here;
        // its lapse is pinned by
        // aFailedForcedRefreshMayRetryAfterTheCooldown).
        assertNull(manager.bearerAfterRejection(STALE_TOKEN))
        assertEquals(1, form.refreshRequests.get())
    }

    @Test
    fun aForeignTokenIsNeverRefreshed() {
        // Above all the just-granted token inside adopt(): its
        // refusal is adoption's own Rejected verdict, and a
        // refresh of the OLD session must never answer for it.
        val form = RoutingFormTransport()
        val manager = primedManager(form, testTokenStore())

        assertNull(manager.bearerAfterRejection("ya29.someone-elses-token"))
        assertEquals(0, form.refreshRequests.get())
    }

    @Test
    fun signedOutThereIsNothingToRefresh() {
        val form = RoutingFormTransport()
        val manager = GoogleSessionManager(
            store = testTokenStore(),
            auth = GoogleNativeAuth(form, NativeTestSupport.ANDROID_ID),
            client = YoutubeiClient(UnusedTransport()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            redirectUri = NativeTestSupport.REDIRECT,
            signInDiagnostics = SignInDiagnostics(),
            nowMs = { NOW },
        )

        assertNull(manager.bearerAfterRejection(STALE_TOKEN))
        assertEquals(0, form.refreshRequests.get())
    }

    @Test
    fun aRefusedTokenWithNoRefreshTokenEndsTheSessionInsteadOfZombifying() {
        // No refresh token + a server refusal = a grant that can
        // never work again. Keeping it stored would leave every
        // surface claiming "signed in" over a dead session.
        val form = RoutingFormTransport()
        val store = testTokenStore()
        val manager = primedManager(form, store, refreshToken = null)

        assertNull(manager.bearerAfterRejection(STALE_TOKEN))

        assertNull(store.load())
        assertFalse(manager.uiState.value.signedIn)
        assertEquals(SessionHealth.NEEDS_SIGN_IN, manager.uiState.value.health)
        assertEquals(0, form.refreshRequests.get())
    }

    // ------------------------------------------------------------------
    // The full stack: real client + decorator + manager
    // ------------------------------------------------------------------

    /** GET recorder for the userinfo endpoint, answered from a script. */
    private class ScriptedGetTransport : HttpTransport {
        val responses = ArrayDeque<HttpResponse>()
        val authorizationHeaders = mutableListOf<String?>()

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse = throw UnsupportedOperationException("GET only")

        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            authorizationHeaders += headers["Authorization"]
            return responses.removeFirst()
        }
    }

    @Test
    fun aMidUse401RefreshesAndRetriesOnceThroughTheRealClient() {
        val form = RoutingFormTransport()
        val manager = primedManager(form, testTokenStore())

        val wire = ScriptedGetTransport()
        wire.responses.add(HttpResponse(401, """{"error":"invalid_token"}"""))
        wire.responses.add(HttpResponse(200, USERINFO_BODY))
        val client = YoutubeiClient(
            BearerRetryTransport(wire) { rejected ->
                manager.bearerAfterRejection(rejected)
            }
        )

        // The token a caller fetched a moment ago — locally valid,
        // already dead at Google (the clock-skew / revoked-between-
        // refreshes shape). Before this pass the lookup landed on
        // Rejected and the session was pronounced dead; now the
        // refusal itself drives the refresh and the retry answers.
        val lookup = client.oauthIdentity(STALE_TOKEN)

        assertTrue(lookup is IdentityLookup.Found)
        val identity = (lookup as IdentityLookup.Found).identities.single()
        assertEquals("Test Listener", identity.name)
        assertEquals(
            listOf("Bearer $STALE_TOKEN", "Bearer $FRESH_TOKEN"),
            wire.authorizationHeaders,
        )
        assertEquals(1, form.refreshRequests.get())
        // The session was NOT ended by the refusal: it stands,
        // refreshed.
        assertTrue(manager.uiState.value.signedIn)
        assertEquals(FRESH_TOKEN, manager.currentBearer())
    }

    @Test
    fun aRevocationDiscoveredMidUseStillEndsTheSessionThroughTheClient() {
        val form = RoutingFormTransport()
        form.onRefresh = {
            HttpResponse(400, """{"error":"invalid_grant"}""")
        }
        val store = testTokenStore()
        val manager = primedManager(form, store)

        val wire = ScriptedGetTransport()
        wire.responses.add(HttpResponse(401, """{"error":"invalid_token"}"""))
        val client = YoutubeiClient(
            BearerRetryTransport(wire) { rejected ->
                manager.bearerAfterRejection(rejected)
            }
        )

        // The forced refresh discovers the revocation, so no retry
        // is offered and the original refusal stands as the verdict:
        // Rejected, with the session honestly ended behind it.
        val lookup = client.oauthIdentity(STALE_TOKEN)

        assertTrue(lookup is IdentityLookup.Rejected)
        assertEquals(1, wire.authorizationHeaders.size)
        assertNull(store.load())
        assertFalse(manager.uiState.value.signedIn)
        assertEquals(SessionHealth.NEEDS_SIGN_IN, manager.uiState.value.health)
    }

    // ------------------------------------------------------------------
    // Exchange endings
    // ------------------------------------------------------------------

    private suspend fun awaitTrue(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(50)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    @Test
    fun anExchangeInvalidGrantEndsTerminallyAndTheNextStartIsFresh() = runBlocking {
        val form = RoutingFormTransport()
        form.onExchange = {
            HttpResponse(400, """{"error":"invalid_grant"}""")
        }
        val tokenStore = testTokenStore()
        val pendingStore = testPendingStore()
        val manager = emptySessionManager(form, tokenStore, pendingStore)

        manager.startSignIn()
        val firstLaunch = manager.authorizationLaunch.value!!
        val firstState = NativeTestSupport.stateFrom(firstLaunch.url)
        manager.completeSignIn(NativeTestSupport.codeRedirect(firstState))
        awaitTrue { manager.phase.value is GoogleSignInPhase.Failed }

        // The dead attempt is GONE — not kept for a zombie resume —
        // and nothing was adopted.
        assertNull(pendingStore.load())
        assertNull(tokenStore.load())
        assertNull(manager.authorizationLaunch.value)

        // Starting again mints a NEW attempt (a fresh state)
        // rather than resuming the dead one.
        manager.startSignIn()
        val secondLaunch = manager.authorizationLaunch.value!!
        assertTrue(
            NativeTestSupport.stateFrom(secondLaunch.url) != firstState
        )
        assertEquals(1, form.exchangeRequests.get())
    }

    @Test
    fun anExpiredPersistedAttemptIsClearedQuietlyAtConstruction() {
        // A persisted attempt that expired while the app was away
        // is cleared at manager construction WITHOUT an ending:
        // the owner never saw this attempt (0.8.8's deliberate
        // divergence from the device flow's expired ending — a
        // browser attempt they may never even have opened must
        // not greet them with a failure for something they didn't
        // do). The sign-in screen simply offers a fresh start.
        val pendingStore = testPendingStore()
        pendingStore.save(
            StoredPendingNativeAuth(
                state = "dead-state",
                codeVerifier = "dead-verifier",
                clientId = NativeTestSupport.ANDROID_ID,
                redirectUri = NativeTestSupport.REDIRECT,
                issuedAtEpochMs = NOW - 1_800_000L,
                expiresAtEpochMs = NOW - 1_000L,
            )
        )
        val manager = emptySessionManager(
            RoutingFormTransport(), testTokenStore(), pendingStore,
        )

        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        assertNull(manager.authorizationLaunch.value)
        assertNull(pendingStore.load())
    }

    /** A manager with NO stored session (the sign-in flow tests). */
    private fun emptySessionManager(
        form: RoutingFormTransport,
        tokenStore: GoogleTokenStore,
        pendingStore: GooglePendingNativeAuthStore,
    ): GoogleSessionManager = GoogleSessionManager(
        store = tokenStore,
        auth = GoogleNativeAuth(form, NativeTestSupport.ANDROID_ID),
        client = YoutubeiClient(UnusedTransport()),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        redirectUri = NativeTestSupport.REDIRECT,
        pendingAttemptStore = pendingStore,
        signInDiagnostics = SignInDiagnostics(),
        nowMs = { NOW },
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val STALE_TOKEN = "ya29.stale"
        const val FRESH_TOKEN = "ya29.fresh"

        const val REFRESHED_BODY =
            """{"access_token":"ya29.fresh","expires_in":3599,""" +
                """"refresh_token":"rt-2","token_type":"Bearer"}"""

        const val GRANT_BODY =
            """{"access_token":"ya29.granted","expires_in":3599,""" +
                """"refresh_token":"rt-1","token_type":"Bearer"}"""

        const val USERINFO_BODY =
            """{"sub":"123","name":"Test Listener",""" +
                """"email":"listener@example.com",""" +
                """"picture":"https://example.com/avatar.png"}"""
    }

    // ---------------------------------------------------------------
    // 0.8.7 sweep 1: the latch's cooldown, and the save retry
    // ---------------------------------------------------------------

    @Test
    fun aFailedForcedRefreshMayRetryAfterTheCooldown() {
        var clock = NOW
        val form = RoutingFormTransport()
        form.onRefresh = {
            HttpResponse(503, """{"error":"temporarily_unavailable"}""")
        }
        val store = testTokenStore()
        // Prime the locally-valid-but-refused session against a
        // mutable clock (primedManager's clock is frozen at NOW;
        // this test needs time to pass).
        store.save(
            StoredGoogleSession(
                accessToken = STALE_TOKEN,
                refreshToken = "rt-1",
                accessTokenExpiresAtEpochMs = NOW + 3_600_000L,
                identityName = "Test Listener",
                identityEmail = "listener@example.com",
                grantedAtEpochMs = NOW,
                lastValidatedEpochMs = NOW,
            ),
        )
        val primed = GoogleSessionManager(
            store = store,
            auth = GoogleNativeAuth(form, NativeTestSupport.ANDROID_ID),
            client = YoutubeiClient(UnusedTransport()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            redirectUri = NativeTestSupport.REDIRECT,
            signInDiagnostics = SignInDiagnostics(),
            nowMs = { clock },
        )

        assertNull(primed.bearerAfterRejection(STALE_TOKEN))
        assertEquals(1, form.refreshRequests.get())
        // Inside the cooldown the refusal spends nothing more.
        assertNull(primed.bearerAfterRejection(STALE_TOKEN))
        assertEquals(1, form.refreshRequests.get())
        // After it, the same refused token may try again — the
        // clock-skew case this path exists for is not bricked by
        // one network blip — and this time Google answers.
        clock += 61_000L
        form.onRefresh = { HttpResponse(200, REFRESHED_BODY) }
        assertEquals(FRESH_TOKEN, primed.bearerAfterRejection(STALE_TOKEN))
        assertEquals(2, form.refreshRequests.get())
    }

    /** A sealed storage whose next write fails, once, when armed. */
    private class FailOnceStorage : SecretStorage {
        private val inner = InMemorySecretStorage()

        @Volatile
        var armed: Boolean = false

        override fun read(): ByteArray? = inner.read()

        override fun write(bytes: ByteArray) {
            if (armed) {
                armed = false
                throw RuntimeException("disk full MARKER")
            }
            inner.write(bytes)
        }

        override fun clear() = inner.clear()
    }

    @Test
    fun aRefreshSaveFailureIsRetriedBeforeTheRotatedTokenIsLost() {
        // The refresh rotates the refresh token (rt-1 -> rt-2)
        // but the sealed save throws: memory takes the new pair,
        // disk keeps the rotated-out one. Without the retry, a
        // restart would present rt-1, read invalid_grant, and
        // wipe a healthy session as "revoked" (0.8.7 sweep 1).
        val form = RoutingFormTransport()
        val storage = FailOnceStorage()
        val store = GoogleTokenStore(storage, testSecretBox())
        store.save(
            StoredGoogleSession(
                accessToken = STALE_TOKEN,
                refreshToken = "rt-1",
                accessTokenExpiresAtEpochMs = NOW + 30_000L,
                identityName = "Test Listener",
                identityEmail = "listener@example.com",
                grantedAtEpochMs = NOW,
                lastValidatedEpochMs = NOW,
            ),
        )
        val manager = GoogleSessionManager(
            store = store,
            auth = GoogleNativeAuth(form, NativeTestSupport.ANDROID_ID),
            client = YoutubeiClient(UnusedTransport()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            redirectUri = NativeTestSupport.REDIRECT,
            signInDiagnostics = SignInDiagnostics(),
            nowMs = { NOW },
        )

        storage.armed = true
        assertEquals(FRESH_TOKEN, manager.currentBearer())
        // The divergence is real: disk still holds the old pair.
        assertEquals("rt-1", store.load()?.refreshToken)
        // The next bearer call retries the save first, so from
        // here a restart loads the rotated pair.
        assertEquals(FRESH_TOKEN, manager.currentBearer())
        assertEquals("rt-2", store.load()?.refreshToken)
        assertEquals(FRESH_TOKEN, store.load()?.accessToken)
    }
}
