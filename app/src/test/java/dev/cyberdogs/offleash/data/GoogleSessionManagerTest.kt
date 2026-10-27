package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleDeviceAuth
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
 * The Google session manager end to end on the JVM (0.7.0): the
 * real [GoogleDeviceAuth] and [YoutubeiClient] run against scripted
 * transports — only the wire is faked. The response bodies are
 * hand-built to Google's documented OAuth/userinfo shapes; the
 * owner's on-device approval is the final proof, not this suite.
 *
 * Covered: the device-flow adoption (code shown, poll converges,
 * tokens sealed with the confirmed identity), the denial ending,
 * proactive refresh, the revoked-grant wipe, and sign-out's
 * revoke + verified wipe.
 */
class GoogleSessionManagerTest {

    /** Form-POST transport for the OAuth endpoints, served from a script. */
    private class ScriptedFormTransport : HttpTransport {
        val responses = ArrayDeque<HttpResponse>()
        var lastFormBody: String? = null
            private set

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
            lastFormBody = formBody
            return responses.removeFirst()
        }
    }

    /** Transport whose GET answers the userinfo endpoint; POSTs are refused. */
    private class UserinfoTransport(
        private val status: Int = 200,
        private val body: String = USERINFO_BODY,
    ) : HttpTransport {
        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse = throw UnsupportedOperationException("GET only")

        override fun get(url: String, headers: Map<String, String>): HttpResponse =
            HttpResponse(status, body)
    }

    private fun manager(
        formTransport: ScriptedFormTransport,
        store: GoogleTokenStore,
        userinfoStatus: Int = 200,
    ): GoogleSessionManager = GoogleSessionManager(
        store = store,
        auth = GoogleDeviceAuth(formTransport),
        client = YoutubeiClient(UserinfoTransport(status = userinfoStatus)),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        signInDiagnostics = SignInDiagnostics(),
    )

    private fun testStore(): GoogleTokenStore =
        GoogleTokenStore(InMemorySecretStorage(), testSecretBox())

    /** Polls [condition] until it holds or [timeoutMs] passes. */
    private suspend fun awaitTrue(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(50)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    @Test
    fun deviceFlowAdoptsTheGrantWithItsConfirmedIdentity() = runBlocking {
        val form = ScriptedFormTransport()
        // interval 0 in the body: the manager floors it at 1s, so two
        // polls cost ~2s of test time.
        form.responses.add(
            HttpResponse(
                200,
                """{"device_code":"dc-1","user_code":"WDJB-MJHT",""" +
                    """"verification_url":"https://www.google.com/device",""" +
                    """"expires_in":1800,"interval":0}""",
            )
        )
        form.responses.add(HttpResponse(400, """{"error":"authorization_pending"}"""))
        form.responses.add(
            HttpResponse(
                200,
                """{"access_token":"ya29.granted","expires_in":3599,""" +
                    """"refresh_token":"rt-1","token_type":"Bearer"}""",
            )
        )
        val store = testStore()
        val manager = manager(form, store)

        manager.startSignIn()
        awaitTrue { manager.phase.value is GoogleSignInPhase.AwaitingApproval }
        val awaiting = manager.phase.value as GoogleSignInPhase.AwaitingApproval
        assertEquals("WDJB-MJHT", awaiting.userCode)
        assertEquals("https://www.google.com/device", awaiting.verificationUrl)

        awaitTrue { manager.uiState.value.signedIn }
        val ui = manager.uiState.value
        assertEquals("Test Listener", ui.identityName)
        assertEquals("listener@example.com", ui.identityEmail)
        assertEquals(SessionHealth.HEALTHY, ui.health)
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)

        val stored = store.load()
        assertTrue("the grant must be sealed in the store", stored != null)
        assertEquals("ya29.granted", stored!!.accessToken)
        assertEquals("rt-1", stored.refreshToken)
        assertEquals("Test Listener", stored.identityName)
        // The adopted bearer is served without a network round trip.
        assertEquals("ya29.granted", manager.currentBearer())
    }

    @Test
    fun deviceFlowDenialEndsTheAttemptAndStoresNothing() = runBlocking {
        val form = ScriptedFormTransport()
        form.responses.add(
            HttpResponse(
                200,
                """{"device_code":"dc-2","user_code":"ABCD-EFGH",""" +
                    """"verification_url":"https://www.google.com/device",""" +
                    """"expires_in":1800,"interval":0}""",
            )
        )
        form.responses.add(HttpResponse(400, """{"error":"access_denied"}"""))
        val store = testStore()
        val manager = manager(form, store)

        manager.startSignIn()
        awaitTrue { manager.phase.value is GoogleSignInPhase.Failed }
        val failed = manager.phase.value as GoogleSignInPhase.Failed
        assertTrue(failed.reason.contains("declined"))
        assertFalse(manager.uiState.value.signedIn)
        assertNull(store.load())
    }

    @Test
    fun currentBearerRefreshesAnExpiringToken() {
        val form = ScriptedFormTransport()
        form.responses.add(
            HttpResponse(
                200,
                """{"access_token":"ya29.fresh","expires_in":3599,"token_type":"Bearer"}""",
            )
        )
        val store = testStore()
        store.save(
            StoredGoogleSession(
                accessToken = "ya29.stale",
                refreshToken = "rt-1",
                // Already expired: the refresh must happen.
                accessTokenExpiresAtEpochMs = 1L,
                identityName = "Test Listener",
            )
        )
        val manager = manager(form, store)
        assertEquals("ya29.fresh", manager.currentBearer())
        assertTrue(form.lastFormBody!!.contains("grant_type=refresh_token"))
        val stored = store.load()!!
        assertEquals("ya29.fresh", stored.accessToken)
        // No rotation in the answer: the stored refresh token stands.
        assertEquals("rt-1", stored.refreshToken)
    }

    @Test
    fun currentBearerOnRevokedGrantWipesAndReportsNeedsSignIn() {
        val form = ScriptedFormTransport()
        form.responses.add(HttpResponse(400, """{"error":"invalid_grant"}"""))
        val store = testStore()
        store.save(
            StoredGoogleSession(
                accessToken = "ya29.stale",
                refreshToken = "rt-dead",
                accessTokenExpiresAtEpochMs = 1L,
            )
        )
        val manager = manager(form, store)
        assertNull(manager.currentBearer())
        assertTrue(store.isWipedClean())
        assertFalse(manager.uiState.value.signedIn)
        assertEquals(SessionHealth.NEEDS_SIGN_IN, manager.uiState.value.health)
    }

    @Test
    fun signOutRevokesAndWipes() = runBlocking {
        val form = ScriptedFormTransport()
        form.responses.add(HttpResponse(200, ""))
        val store = testStore()
        store.save(
            StoredGoogleSession(
                accessToken = "ya29.live",
                refreshToken = "rt-live",
                accessTokenExpiresAtEpochMs = Long.MAX_VALUE,
                identityName = "Test Listener",
            )
        )
        val manager = manager(form, store)
        assertTrue(manager.uiState.value.signedIn)

        manager.signOut()
        // Revocation targets the refresh token (the whole grant).
        assertTrue(form.lastFormBody!!.contains("token=rt-live"))
        assertTrue(store.isWipedClean())
        assertFalse(manager.uiState.value.signedIn)
        assertNull(manager.currentBearer())
    }

    private companion object {
        const val USERINFO_BODY =
            """{"sub":"123","name":"Test Listener",""" +
                """"email":"listener@example.com",""" +
                """"picture":"https://example.com/avatar.png"}"""
    }
}
