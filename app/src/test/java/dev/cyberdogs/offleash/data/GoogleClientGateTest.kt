package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleDeviceAuth
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * The 0.8.6 client-change gate, end to end on the JVM: sessions
 * sealed under one client class end cleanly — once, with the
 * honest reason — when the live identity is another class or
 * none; sessions sealed under the live class carry untouched;
 * a stale pending grant from another class is cleared instead
 * of resumed; adoption stamps the issuing class; and a sign-in
 * attempt in an unconfigured build is refused with the plain
 * reason before anything is asked of Google.
 */
class GoogleClientGateTest {

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

    /** Transport whose GET answers the userinfo endpoint. */
    private class UserinfoTransport : HttpTransport {
        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse = throw UnsupportedOperationException("GET only")

        override fun get(
            url: String,
            headers: Map<String, String>,
        ): HttpResponse = HttpResponse(200, USERINFO_BODY)
    }

    private fun manager(
        auth: GoogleDeviceAuth,
        store: GoogleTokenStore,
        diagnostics: SignInDiagnostics = SignInDiagnostics(),
        grantStore: GooglePendingGrantStore? = null,
    ): GoogleSessionManager = GoogleSessionManager(
        store = store,
        auth = auth,
        client = YoutubeiClient(UserinfoTransport()),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        pendingGrantStore = grantStore,
        signInDiagnostics = diagnostics,
    )

    private fun testStore(): GoogleTokenStore =
        GoogleTokenStore(InMemorySecretStorage(), testSecretBox())

    private fun testGrantStore(): GooglePendingGrantStore =
        GooglePendingGrantStore(InMemorySecretStorage(), testSecretBox())

    private fun seededStore(clientKind: String?): GoogleTokenStore {
        val store = testStore()
        store.save(
            StoredGoogleSession(
                accessToken = "ya29.seeded",
                refreshToken = "rt-seeded",
                accessTokenExpiresAtEpochMs =
                    System.currentTimeMillis() + 3_600_000L,
                identityName = "Test Listener",
                identityEmail = "listener@example.com",
                clientKind = clientKind,
            ),
        )
        return store
    }

    private suspend fun awaitTrue(
        timeoutMs: Long = 15_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(50)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    @Test
    fun legacySessionIsCleanlyResignedUnderAnOwnerBuild() {
        val diagnostics = SignInDiagnostics()
        val store = seededStore(clientKind = null)
        val auth = GoogleDeviceAuth(
            ScriptedFormTransport(),
            TestOAuthIdentity.SHARED,
        )
        val manager = manager(auth, store, diagnostics)
        assertTrue(manager.uiState.value.signedIn)

        // The 0.8.6 switch itself: the live identity becomes the
        // owner's pair and the gate runs, exactly as at startup.
        auth.identity = TestOAuthIdentity.OWNER
        manager.applyClientGate()

        assertNull(store.load())
        assertFalse(manager.uiState.value.signedIn)
        assertEquals(
            CLIENT_CHANGED_REASON,
            manager.uiState.value.lastEndReason,
        )
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        assertTrue(
            diagnostics.lines().any { it.contains("signin: client=owner") },
        )
    }

    @Test
    fun ownerSessionCarriesUnderAnOwnerBuild() {
        val store = seededStore(clientKind = "owner")
        val auth = GoogleDeviceAuth(
            ScriptedFormTransport(),
            TestOAuthIdentity.OWNER,
        )
        val manager = manager(auth, store)
        manager.applyClientGate()
        assertTrue(store.load() != null)
        assertTrue(manager.uiState.value.signedIn)
        assertNull(manager.uiState.value.lastEndReason)
    }

    @Test
    fun legacySessionCarriesUnderASharedBuild() {
        val store = seededStore(clientKind = null)
        val auth = GoogleDeviceAuth(
            ScriptedFormTransport(),
            TestOAuthIdentity.SHARED,
        )
        val manager = manager(auth, store)
        manager.applyClientGate()
        assertTrue(store.load() != null)
        assertTrue(manager.uiState.value.signedIn)
    }

    @Test
    fun anySessionIsClearedWhenTheBuildHasNoClient() {
        val diagnostics = SignInDiagnostics()
        val store = seededStore(clientKind = "owner")
        val auth = GoogleDeviceAuth(ScriptedFormTransport(), null)
        val manager = manager(auth, store, diagnostics)
        manager.applyClientGate()
        assertNull(store.load())
        assertFalse(manager.uiState.value.signedIn)
        assertEquals(
            UNCONFIGURED_END_REASON,
            manager.uiState.value.lastEndReason,
        )
        assertTrue(
            diagnostics.lines()
                .any { it.contains("signin: client=unconfigured") },
        )
    }

    @Test
    fun theGateEndsASessionOnceNotRepeatedly() {
        val store = seededStore(clientKind = null)
        val auth = GoogleDeviceAuth(
            ScriptedFormTransport(),
            TestOAuthIdentity.OWNER,
        )
        val manager = manager(auth, store)
        manager.applyClientGate()
        val reasonAfterFirst = manager.uiState.value.lastEndReason
        manager.applyClientGate()
        assertEquals(reasonAfterFirst, manager.uiState.value.lastEndReason)
        assertEquals(
            CLIENT_CHANGED_REASON,
            reasonAfterFirst,
        )
    }

    @Test
    fun aStaleGrantFromAnotherClassIsClearedNotResumed() {
        val grantStore = testGrantStore()
        val now = System.currentTimeMillis()
        grantStore.save(
            StoredPendingGrant(
                deviceCode = "dc-stale",
                userCode = "WDJB-MJHT",
                verificationUrl = "https://www.google.com/device",
                issuedAtEpochMs = now,
                expiresAtEpochMs = now + 1_800_000L,
                intervalSeconds = 5L,
                clientKind = null,
            ),
        )
        val store = testStore()
        val auth = GoogleDeviceAuth(
            ScriptedFormTransport(),
            TestOAuthIdentity.OWNER,
        )
        val manager = manager(auth, store, grantStore = grantStore)
        manager.applyClientGate()
        assertNull(grantStore.load())
    }

    @Test
    fun adoptionStampsTheIssuingClientClass() = runBlocking {
        val form = ScriptedFormTransport()
        form.responses.add(
            HttpResponse(
                200,
                """{"device_code":"dc-1","user_code":"WDJB-MJHT",""" +
                    """"verification_url":"https://www.google.com/device",""" +
                    """"expires_in":1800,"interval":0}""",
            ),
        )
        form.responses.add(
            HttpResponse(
                200,
                """{"access_token":"ya29.granted","expires_in":3599,""" +
                    """"refresh_token":"rt-1","token_type":"Bearer"}""",
            ),
        )
        val store = testStore()
        val auth = GoogleDeviceAuth(form, TestOAuthIdentity.OWNER)
        val manager = manager(auth, store)

        manager.startSignIn()
        awaitTrue { manager.uiState.value.signedIn }
        assertEquals("owner", store.load()?.clientKind)
        assertTrue(
            (form.lastFormBody ?: "")
                .contains("client_id=fake-owner-client-id"),
        )
    }

    @Test
    fun signInIsRefusedHonestlyWhenNoClientIsConfigured() {
        val store = testStore()
        val auth = GoogleDeviceAuth(ScriptedFormTransport(), null)
        val manager = manager(auth, store)
        manager.startSignIn()
        val phase = manager.phase.value
        assertTrue(phase is GoogleSignInPhase.Failed)
        assertEquals(
            NOT_CONFIGURED_REASON,
            (phase as GoogleSignInPhase.Failed).reason,
        )
    }

    private companion object {

        // The gate's landing copy, pinned literally: the manager's
        // companion is private, and the wording is a product
        // surface — a change to it should fail here on purpose.
        const val CLIENT_CHANGED_REASON =
            "You were signed out because OffLeash's sign-in " +
                "changed. Sign in again to reconnect your account."
        const val UNCONFIGURED_END_REASON =
            "You were signed out because this build of OffLeash " +
                "has no sign-in set up. Set up your own Google key " +
                "from the sign-in screen, then sign in again."
        const val NOT_CONFIGURED_REASON =
            "Google sign-in isn't set up in this build of " +
                "OffLeash yet. Set up your own Google key — it's " +
                "free and takes about 10 minutes — using the card " +
                "below, then sign in."

        const val USERINFO_BODY =
            """{"sub":"123","name":"Test Listener",""" +
                """"email":"listener@example.com",""" +
                """"picture":"https://example.com/avatar.png"}"""
    }

    // ---------------------------------------------------------------
    // 0.8.7 sweep 1: the gate against work still in flight
    // ---------------------------------------------------------------

    /** A form transport that blocks inside the issuance request. */
    private class LatchingFormTransport : HttpTransport {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

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
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            return HttpResponse(
                200,
                """{"device_code":"dc-1","user_code":"WDJB-MJHT",""" +
                    """"verification_url":"https://www.google.com/device",""" +
                    """"expires_in":1800,"interval":5}""",
            )
        }
    }

    @Test
    fun anIssuanceInFlightAcrossAGateCallNeverPersists() = runBlocking {
        // The zombie shape (0.8.7 sweep 1): a sign-in is blocked
        // in its issuance request when the client changes (the
        // wizard saves a key). Nothing is persisted yet, so the
        // class branches of the gate see nothing to end — the
        // epoch move in the nothing-stands branch is the only
        // thing standing between this run and a grant stamped
        // with the NEW client's class, polled against the wrong
        // registration until expiry.
        val form = LatchingFormTransport()
        val grantStore = testGrantStore()
        val auth = GoogleDeviceAuth(form, TestOAuthIdentity.SHARED)
        val manager = manager(auth, testStore(), grantStore = grantStore)

        manager.startSignIn()
        assertTrue(form.entered.await(5, TimeUnit.SECONDS))
        manager.applyClientGate(forceCleanResign = true)
        form.release.countDown()
        delay(600)

        assertNull(grantStore.load())
        assertFalse(
            manager.phase.value is GoogleSignInPhase.AwaitingApproval
        )
        assertFalse(manager.uiState.value.signedIn)
    }
}
