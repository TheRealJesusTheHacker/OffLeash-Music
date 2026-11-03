package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleNativeAuth
import dev.cyberdogs.offleash.core.auth.GoogleOAuth
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Bug sweep round 2 (lane C3): repeated lifecycle churn and clock
 * chaos against the Google session manager, with fake transports —
 * carried to the native flow (0.8.8).
 *
 * - 200 sign-in → sign-out cycles: no state leaks — the token file
 *   and the pending-attempt file are wiped every cycle, exactly
 *   one exchange and one revocation per cycle, and a fresh
 *   manager over the same stores afterwards is cleanly signed out
 *   with no zombie attempt to resume.
 * - Clock jumps across a pending attempt: +1h past its expiry
 *   clears it quietly at restore with zero network; −1h leaves it
 *   locally live, but the SERVER's verdict on the returned code
 *   (invalid_grant — the code is dead server-side) is what ends
 *   it. The local clock never keeps a dead attempt alive.
 * - Clock jumps across token expiry: −10min forces no refresh
 *   (the stored expiry and the clock move together); a jump past
 *   expiry with a revoked refresh grant ends the session honestly.
 */
class GoogleChurnSweepTest {

    /** Form transport: exchanges grant at once; refresh/revoke scripted + counted. */
    private class ChurnTransport : HttpTransport {
        val exchangeRequests = AtomicInteger(0)
        val refreshRequests = AtomicInteger(0)
        val revokeRequests = AtomicInteger(0)

        @Volatile
        var onExchange: () -> HttpResponse = {
            HttpResponse(
                200,
                """{"access_token":"ya29.granted","expires_in":3599,""" +
                    """"refresh_token":"rt-1","token_type":"Bearer"}""",
            )
        }

        @Volatile
        var onRefresh: () -> HttpResponse = {
            HttpResponse(400, """{"error":"invalid_grant"}""")
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
            url == GoogleOAuth.REVOKE_URL -> {
                revokeRequests.incrementAndGet()
                HttpResponse(200, "")
            }
            else -> throw AssertionError("unexpected form POST: $url")
        }

        override fun get(url: String, headers: Map<String, String>): HttpResponse =
            HttpResponse(200, USERINFO_BODY)
    }

    private fun manager(
        form: ChurnTransport,
        tokenStore: GoogleTokenStore,
        attemptStore: GooglePendingNativeAuthStore,
        scope: CoroutineScope,
        nowMs: () -> Long = { System.currentTimeMillis() },
    ): GoogleSessionManager = GoogleSessionManager(
        store = tokenStore,
        auth = GoogleNativeAuth(form, NativeTestSupport.ANDROID_ID),
        client = YoutubeiClient(form),
        scope = scope,
        redirectUri = NativeTestSupport.REDIRECT,
        pendingAttemptStore = attemptStore,
        signInDiagnostics = SignInDiagnostics(),
        nowMs = nowMs,
    )

    private suspend fun awaitTrue(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(10)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    @Test
    fun twoHundredSignInSignOutCyclesLeaveNoStateBehind() = runBlocking {
        val form = ChurnTransport()
        val box = testSecretBox()
        val tokenStorage = InMemorySecretStorage()
        val attemptStorage = InMemorySecretStorage()
        val tokenStore = GoogleTokenStore(tokenStorage, box)
        val attemptStore = GooglePendingNativeAuthStore(attemptStorage, box)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = manager(form, tokenStore, attemptStore, scope)
            repeat(200) { cycle ->
                manager.startSignIn()
                awaitTrue {
                    manager.phase.value is GoogleSignInPhase.AwaitingReturn
                }
                val launch = manager.authorizationLaunch.value!!
                manager.completeSignIn(
                    NativeTestSupport.codeRedirect(
                        NativeTestSupport.stateFrom(launch.url)
                    )
                )
                awaitTrue { manager.uiState.value.signedIn }
                assertTrue(
                    "cycle $cycle: tokens must be stored while signed in",
                    tokenStore.load() != null,
                )
                manager.signOut()
                awaitTrue { !manager.uiState.value.signedIn }
                assertTrue(
                    "cycle $cycle: token store must be wiped at sign-out",
                    tokenStore.isWipedClean(),
                )
                assertTrue(
                    "cycle $cycle: no pending attempt may survive sign-out",
                    attemptStore.isWipedClean(),
                )
            }
            // Exactly one exchange and one revocation per cycle —
            // no leaked work re-issuing behind the cycles' backs.
            assertEquals(200, form.exchangeRequests.get())
            assertEquals(200, form.revokeRequests.get())
            // A fresh manager (process restart) over the same
            // stores is signed out and resumes nothing.
            val fresh = manager(form, tokenStore, attemptStore, scope)
            assertFalse(fresh.uiState.value.signedIn)
            assertFalse(fresh.hasStoredSession())
            assertEquals(GoogleSignInPhase.Idle, fresh.phase.value)
            assertNull(fresh.authorizationLaunch.value)
            assertEquals(200, form.exchangeRequests.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun clockJumpForwardPastAttemptExpiryClearsItAtRestoreQuietly() = runBlocking {
        val form = ChurnTransport()
        val box = testSecretBox()
        val attemptStore =
            GooglePendingNativeAuthStore(InMemorySecretStorage(), box)
        val issuedAt = 1_700_000_000_000L
        attemptStore.save(
            StoredPendingNativeAuth(
                state = "old-state",
                codeVerifier = "old-verifier",
                clientId = NativeTestSupport.ANDROID_ID,
                redirectUri = NativeTestSupport.REDIRECT,
                issuedAtEpochMs = issuedAt,
                expiresAtEpochMs = issuedAt + 30 * 60_000L,
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // The device clock jumps +1h while the app is dead: at
            // restore the attempt is expired — cleared quietly
            // (no failure ending for an attempt the owner may
            // never have opened; 0.8.8's divergence from the
            // device flow) and never completed (no zombie).
            val manager = manager(
                form,
                GoogleTokenStore(InMemorySecretStorage(), box),
                attemptStore,
                scope,
                nowMs = { issuedAt + 61 * 60_000L },
            )
            assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
            assertNull(manager.authorizationLaunch.value)
            assertTrue(attemptStore.isWipedClean())
            delay(300)
            assertEquals(0, form.exchangeRequests.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun clockJumpBackwardLeavesAttemptLocallyLiveButTheServersVerdictEndsIt() =
        runBlocking {
            // The exchange answers invalid_grant — Google's
            // server-side verdict on a code whose life has ended.
            val form = ChurnTransport()
            form.onExchange = {
                HttpResponse(400, """{"error":"invalid_grant"}""")
            }
            val box = testSecretBox()
            val attemptStore =
                GooglePendingNativeAuthStore(InMemorySecretStorage(), box)
            val issuedAt = 1_700_000_000_000L
            attemptStore.save(
                StoredPendingNativeAuth(
                    state = "old-state",
                    codeVerifier = "old-verifier",
                    clientId = NativeTestSupport.ANDROID_ID,
                    redirectUri = NativeTestSupport.REDIRECT,
                    issuedAtEpochMs = issuedAt - 25 * 60_000L,
                    expiresAtEpochMs = issuedAt + 5 * 60_000L,
                ),
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                // Clock at −1h: locally the attempt still looks
                // live, so it is resumed — and when its return
                // arrives, the server's verdict ends it honestly
                // with the store wiped. The local clock never gets
                // to keep a dead attempt alive.
                val manager = manager(
                    form,
                    GoogleTokenStore(InMemorySecretStorage(), box),
                    attemptStore,
                    scope,
                    nowMs = { issuedAt - 60 * 60_000L },
                )
                assertTrue(
                    manager.phase.value is GoogleSignInPhase.AwaitingReturn
                )
                val launch = manager.authorizationLaunch.value!!
                manager.completeSignIn(
                    NativeTestSupport.codeRedirect(
                        NativeTestSupport.stateFrom(launch.url)
                    )
                )
                awaitTrue { manager.phase.value is GoogleSignInPhase.Failed }
                assertTrue(attemptStore.isWipedClean())
                assertEquals(1, form.exchangeRequests.get())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun clockJumpsAcrossTokenExpiryBehaveHonestly() {
        val form = ChurnTransport()
        val box = testSecretBox()
        val tokenStore = GoogleTokenStore(InMemorySecretStorage(), box)
        val grantTime = 1_700_000_000_000L
        val expiry = grantTime + 30 * 60_000L
        tokenStore.save(
            StoredGoogleSession(
                accessToken = "ya29.stored",
                refreshToken = "rt-1",
                accessTokenExpiresAtEpochMs = expiry,
                grantedAtEpochMs = grantTime,
            ),
        )
        val now = AtomicLong(grantTime + 10 * 60_000L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = manager(
                form,
                tokenStore,
                GooglePendingNativeAuthStore(InMemorySecretStorage(), box),
                scope,
                nowMs = { now.get() },
            )
            // Clock jumps −10min: stored expiry and the clock moved
            // together, so the token is still outside the 60s skew
            // and is served with NO refresh attempt.
            now.set(grantTime)
            assertEquals("ya29.stored", manager.currentBearer())
            assertEquals(0, form.refreshRequests.get())
            // Clock jumps past the real expiry and the refresh
            // grant is revoked server-side: the session ends
            // honestly — store wiped, no token served,
            // NEEDS_SIGN_IN state.
            now.set(expiry + 60_000L)
            assertNull(manager.currentBearer())
            assertEquals(1, form.refreshRequests.get())
            assertTrue(tokenStore.isWipedClean())
            assertFalse(manager.uiState.value.signedIn)
            assertEquals(SessionHealth.NEEDS_SIGN_IN, manager.uiState.value.health)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val USERINFO_BODY =
            """{"sub":"123","name":"Test Listener",""" +
                """"email":"listener@example.com",""" +
                """"picture":"https://example.com/avatar.png"}"""
    }
}
