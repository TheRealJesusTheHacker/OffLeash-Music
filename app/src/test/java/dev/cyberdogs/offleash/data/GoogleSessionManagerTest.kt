package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleNativeAuth
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
 * The Google session manager end to end on the JVM (0.8.8): the
 * real [GoogleNativeAuth] and [YoutubeiClient] run against
 * scripted transports — only the wire is faked. The response
 * bodies are hand-built to Google's documented OAuth/userinfo
 * shapes; the owner's on-device sign-in is the final proof, not
 * this suite.
 *
 * Covered: the native adoption (attempt launched, the return's
 * code exchanged, tokens sealed with the confirmed identity and
 * the issuing registration), the denial ending, a state-mismatched
 * return refused, proactive refresh, the revoked-grant wipe, and
 * sign-out's revoke + verified wipe.
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
        auth = GoogleNativeAuth(formTransport, NativeTestSupport.ANDROID_ID),
        client = YoutubeiClient(UserinfoTransport(status = userinfoStatus)),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        redirectUri = NativeTestSupport.REDIRECT,
        pendingAttemptStore = NativeTestSupport.attemptStore(),
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
    fun nativeFlowAdoptsTheGrantWithItsConfirmedIdentity() = runBlocking {
        val form = ScriptedFormTransport()
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
        // No network has happened yet: the attempt is built locally
        // and published for the browser leg.
        assertTrue(manager.phase.value is GoogleSignInPhase.AwaitingReturn)
        val launch = manager.authorizationLaunch.value
        assertTrue("a launch must be published", launch != null)
        assertTrue(launch!!.url.startsWith("https://accounts.google.com/"))
        assertTrue(manager.pendingAttemptDurability.value)

        manager.completeSignIn(
            NativeTestSupport.codeRedirect(NativeTestSupport.stateFrom(launch.url))
        )
        awaitTrue { manager.uiState.value.signedIn }
        val ui = manager.uiState.value
        assertEquals("Test Listener", ui.identityName)
        assertEquals("listener@example.com", ui.identityEmail)
        assertEquals(SessionHealth.HEALTHY, ui.health)
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        assertNull(manager.authorizationLaunch.value)

        val stored = store.load()
        assertTrue("the grant must be sealed in the store", stored != null)
        assertEquals("ya29.granted", stored!!.accessToken)
        assertEquals("rt-1", stored.refreshToken)
        assertEquals("Test Listener", stored.identityName)
        // The session names its issuing registration (0.8.8): the
        // android class and the exact client ID, for the gate.
        assertEquals("android", stored.clientKind)
        assertEquals(NativeTestSupport.ANDROID_ID, stored.issuingClientId)
        // The adopted bearer is served without a network round trip.
        assertEquals("ya29.granted", manager.currentBearer())
    }

    @Test
    fun nativeFlowDenialEndsTheAttemptAndStoresNothing() = runBlocking {
        val form = ScriptedFormTransport()
        val store = testStore()
        val manager = manager(form, store)

        manager.startSignIn()
        val launch = manager.authorizationLaunch.value!!
        manager.completeSignIn(
            NativeTestSupport.deniedRedirect(NativeTestSupport.stateFrom(launch.url))
        )
        awaitTrue { manager.phase.value is GoogleSignInPhase.Failed }
        val failed = manager.phase.value as GoogleSignInPhase.Failed
        assertTrue(failed.reason.contains("declined"))
        assertFalse(manager.uiState.value.signedIn)
        assertNull(store.load())
    }

    @Test
    fun returnWithTheWrongStateIsRefusedAndStoresNothing() = runBlocking {
        val form = ScriptedFormTransport()
        val store = testStore()
        val manager = manager(form, store)

        manager.startSignIn()
        manager.completeSignIn(
            NativeTestSupport.codeRedirect(state = "not-the-attempts-state")
        )
        awaitTrue { manager.phase.value is GoogleSignInPhase.Failed }
        val failed = manager.phase.value as GoogleSignInPhase.Failed
        assertTrue(failed.reason.contains("doesn't match"))
        assertFalse(manager.uiState.value.signedIn)
        assertNull(store.load())
        // The exchange is never even attempted for a foreign return.
        assertTrue(form.responses.isEmpty())
        assertNull(form.lastFormBody)
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
