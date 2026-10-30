package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleDeviceAuth
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
 * chaos against the Google session manager, with fake transports.
 *
 * - 200 sign-in → sign-out cycles: no state leaks — the token file
 *   and the pending-grant file are wiped every cycle, exactly one
 *   grant is issued and one revocation sent per cycle, and a fresh
 *   manager over the same stores afterwards is cleanly signed out
 *   with no zombie grant to resume.
 * - Clock jumps across a pending grant: +1h kills it honestly at
 *   restore with zero polls; −1h leaves it locally live, but the
 *   SERVER's expired answer is what ends it (Google expires grants
 *   server-side; the poll mapping handles it).
 * - Clock jumps across token expiry: −10min forces no refresh (the
 *   stored expiry and the clock move together); a jump past expiry
 *   with a revoked refresh grant ends the session honestly.
 */
class GoogleChurnSweepTest {

    /** Form transport: issuance counted, polls grant at once, refresh/revoke scripted. */
    private class ChurnTransport : HttpTransport {
        val deviceCodeRequests = AtomicInteger(0)
        val pollRequests = AtomicInteger(0)
        val refreshRequests = AtomicInteger(0)
        val revokeRequests = AtomicInteger(0)

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
            url == GoogleOAuth.DEVICE_CODE_URL -> {
                deviceCodeRequests.incrementAndGet()
                HttpResponse(200, ISSUE_BODY)
            }
            url == GoogleOAuth.TOKEN_URL && formBody.contains("device_code=") -> {
                pollRequests.incrementAndGet()
                HttpResponse(
                    200,
                    """{"access_token":"ya29.granted","expires_in":3599,""" +
                        """"refresh_token":"rt-1","token_type":"Bearer"}""",
                )
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
        pendingStore: GooglePendingGrantStore,
        scope: CoroutineScope,
        nowMs: () -> Long = { System.currentTimeMillis() },
    ): GoogleSessionManager = GoogleSessionManager(
        store = tokenStore,
        auth = GoogleDeviceAuth(form),
        client = YoutubeiClient(form),
        scope = scope,
        pendingGrantStore = pendingStore,
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
        val pendingStorage = InMemorySecretStorage()
        val tokenStore = GoogleTokenStore(tokenStorage, box)
        val pendingStore = GooglePendingGrantStore(pendingStorage, box)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = manager(form, tokenStore, pendingStore, scope)
            repeat(200) { cycle ->
                manager.startSignIn()
                awaitTrue { manager.phase.value is GoogleSignInPhase.AwaitingApproval }
                manager.checkNow()
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
                    "cycle $cycle: no pending grant may survive sign-out",
                    pendingStore.isWipedClean(),
                )
            }
            // Exactly one grant issued and one revocation sent per
            // cycle — no leaked poll jobs re-issuing or re-polling
            // behind the cycles' backs.
            assertEquals(200, form.deviceCodeRequests.get())
            assertEquals(200, form.revokeRequests.get())
            val pollsAfterCycles = form.pollRequests.get()
            delay(300)
            assertEquals(
                "no zombie poll may run after the last sign-out",
                pollsAfterCycles,
                form.pollRequests.get(),
            )
            // A fresh manager (process restart) over the same stores
            // is signed out and resumes nothing.
            val fresh = manager(form, tokenStore, pendingStore, scope)
            assertFalse(fresh.uiState.value.signedIn)
            assertFalse(fresh.hasStoredSession())
            assertEquals(GoogleSignInPhase.Idle, fresh.phase.value)
            assertEquals(200, form.deviceCodeRequests.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun clockJumpForwardPastGrantExpiryClearsItAtRestoreWithNoPolling() = runBlocking {
        val form = ChurnTransport()
        val box = testSecretBox()
        val pendingStore = GooglePendingGrantStore(InMemorySecretStorage(), box)
        val issuedAt = 1_700_000_000_000L
        pendingStore.save(
            StoredPendingGrant(
                deviceCode = "dc-old",
                userCode = "WDJB-MJHT",
                verificationUrl = "https://www.google.com/device",
                issuedAtEpochMs = issuedAt,
                expiresAtEpochMs = issuedAt + 30 * 60_000L,
                intervalSeconds = 5L,
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // The device clock jumps +1h while the app is dead: at
            // restore the grant is expired — cleared, landed on the
            // plain expired ending, and NEVER polled (no zombie).
            val manager = manager(
                form,
                GoogleTokenStore(InMemorySecretStorage(), box),
                pendingStore,
                scope,
                nowMs = { issuedAt + 61 * 60_000L },
            )
            assertTrue(manager.phase.value is GoogleSignInPhase.Failed)
            assertTrue(pendingStore.isWipedClean())
            delay(300)
            assertEquals(0, form.pollRequests.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun clockJumpBackwardLeavesGrantLocallyLiveButTheServersExpiredAnswerEndsIt() = runBlocking {
        // Polls answer "expired" — Google's server-side verdict.
        val form = object : HttpTransport {
            val polls = AtomicInteger(0)

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
                url == GoogleOAuth.TOKEN_URL && formBody.contains("device_code=") -> {
                    polls.incrementAndGet()
                    HttpResponse(400, """{"error":"expired_token"}""")
                }
                else -> throw AssertionError("unexpected form POST: $url")
            }

            override fun get(url: String, headers: Map<String, String>): HttpResponse =
                HttpResponse(200, USERINFO_BODY)
        }
        val box = testSecretBox()
        val pendingStore = GooglePendingGrantStore(InMemorySecretStorage(), box)
        val issuedAt = 1_700_000_000_000L
        pendingStore.save(
            StoredPendingGrant(
                deviceCode = "dc-old",
                userCode = "WDJB-MJHT",
                verificationUrl = "https://www.google.com/device",
                issuedAtEpochMs = issuedAt - 25 * 60_000L,
                expiresAtEpochMs = issuedAt + 5 * 60_000L,
                intervalSeconds = 5L,
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // Clock at −1h: locally the grant still looks live, so
            // polling resumes — and the server's expired answer is
            // mapped to the honest expired ending + a wiped store.
            // The local clock never gets to keep a dead grant alive.
            val manager = GoogleSessionManager(
                store = GoogleTokenStore(InMemorySecretStorage(), box),
                auth = GoogleDeviceAuth(form),
                client = YoutubeiClient(form),
                scope = scope,
                pendingGrantStore = pendingStore,
                signInDiagnostics = SignInDiagnostics(),
                nowMs = { issuedAt - 60 * 60_000L },
            )
            assertTrue(manager.phase.value is GoogleSignInPhase.AwaitingApproval)
            manager.checkNow()
            awaitTrue { manager.phase.value is GoogleSignInPhase.Failed }
            assertTrue(pendingStore.isWipedClean())
            assertTrue(form.polls.get() >= 1)
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
                GooglePendingGrantStore(InMemorySecretStorage(), box),
                scope,
                nowMs = { now.get() },
            )
            // Clock jumps −10min: stored expiry and the clock moved
            // together, so the token is still outside the 60s skew
            // and is served with NO refresh attempt.
            now.set(grantTime)
            assertEquals("ya29.stored", manager.currentBearer())
            assertEquals(0, form.refreshRequests.get())
            // Clock jumps past the real expiry and the refresh grant
            // is revoked server-side: the session ends honestly —
            // store wiped, no token served, NEEDS_SIGN_IN state.
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
        /** interval 0: the manager floors the poll cadence at 1s; checkNow wakes it. */
        const val ISSUE_BODY =
            """{"device_code":"dc-1","user_code":"WDJB-MJHT",""" +
                """"verification_url":"https://www.google.com/device",""" +
                """"expires_in":1800,"interval":0}"""

        const val USERINFO_BODY =
            """{"sub":"123","name":"Test Listener",""" +
                """"email":"listener@example.com",""" +
                """"picture":"https://example.com/avatar.png"}"""
    }
}
