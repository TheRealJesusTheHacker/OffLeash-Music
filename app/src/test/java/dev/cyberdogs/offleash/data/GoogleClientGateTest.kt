package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleNativeAuth
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
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
 * The client-change gate, end to end on the JVM (0.8.8): the
 * native build's registration is its Android client ID, fixed at
 * build time. Sessions sealed under a device-era class (owner /
 * shared / unmarked legacy) or under a DIFFERENT Android
 * registration end cleanly — once, with the honest reason — when
 * the gate runs; a session sealed under THIS registration carries
 * untouched; a stale pending attempt from another registration is
 * cleared instead of resumed; adoption stamps the issuing class
 * AND the issuing client ID; and a sign-in attempt in an
 * unconfigured build is refused with the plain reason before
 * anything is asked of Google.
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
        auth: GoogleNativeAuth,
        store: GoogleTokenStore,
        diagnostics: SignInDiagnostics = SignInDiagnostics(),
        attemptStore: GooglePendingNativeAuthStore? = null,
    ): GoogleSessionManager = GoogleSessionManager(
        store = store,
        auth = auth,
        client = YoutubeiClient(UserinfoTransport()),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        redirectUri = NativeTestSupport.REDIRECT,
        pendingAttemptStore = attemptStore,
        signInDiagnostics = diagnostics,
    )

    private fun nativeAuth(
        transport: HttpTransport = ScriptedFormTransport(),
        clientId: String? = NativeTestSupport.ANDROID_ID,
    ): GoogleNativeAuth = GoogleNativeAuth(transport, clientId)

    private fun testStore(): GoogleTokenStore =
        GoogleTokenStore(InMemorySecretStorage(), testSecretBox())

    private fun seededStore(
        clientKind: String?,
        issuingClientId: String? = null,
    ): GoogleTokenStore {
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
                issuingClientId = issuingClientId,
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
    fun legacySessionIsCleanlyResignedUnderANativeBuild() {
        val diagnostics = SignInDiagnostics()
        val store = seededStore(clientKind = null)
        val manager = manager(nativeAuth(), store, diagnostics)
        assertTrue(manager.uiState.value.signedIn)

        manager.applyClientGate()

        assertNull(store.load())
        assertFalse(manager.uiState.value.signedIn)
        assertEquals(
            CLIENT_CHANGED_REASON,
            manager.uiState.value.lastEndReason,
        )
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        assertTrue(
            diagnostics.lines().any { it.contains("signin: client=android") },
        )
    }

    @Test
    fun deviceEraOwnerSessionEndsUnderANativeBuild() {
        val store = seededStore(clientKind = "owner")
        val manager = manager(nativeAuth(), store)
        manager.applyClientGate()
        assertNull(store.load())
        assertFalse(manager.uiState.value.signedIn)
        assertEquals(
            CLIENT_CHANGED_REASON,
            manager.uiState.value.lastEndReason,
        )
    }

    @Test
    fun androidSessionUnderTheSameRegistrationCarries() {
        val store = seededStore(
            clientKind = "android",
            issuingClientId = NativeTestSupport.ANDROID_ID,
        )
        val manager = manager(nativeAuth(), store)
        manager.applyClientGate()
        assertTrue(store.load() != null)
        assertTrue(manager.uiState.value.signedIn)
        assertNull(manager.uiState.value.lastEndReason)
    }

    @Test
    fun androidSessionUnderADifferentRegistrationEnds() {
        // The class alone cannot carry this compare (0.8.7 B-H3):
        // two Android registrations are the same class, and the
        // sealed tokens refresh only under their own.
        val store = seededStore(
            clientKind = "android",
            issuingClientId = NativeTestSupport.OTHER_ANDROID_ID,
        )
        val manager = manager(nativeAuth(), store)
        manager.applyClientGate()
        assertNull(store.load())
        assertFalse(manager.uiState.value.signedIn)
        assertEquals(
            CLIENT_CHANGED_REASON,
            manager.uiState.value.lastEndReason,
        )
    }

    @Test
    fun anySessionIsClearedWhenTheBuildHasNoClient() {
        val diagnostics = SignInDiagnostics()
        val store = seededStore(
            clientKind = "android",
            issuingClientId = NativeTestSupport.ANDROID_ID,
        )
        val manager = manager(nativeAuth(clientId = null), store, diagnostics)
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
        val manager = manager(nativeAuth(), store)
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
    fun aStaleAttemptFromAnotherRegistrationIsClearedNotResumed() {
        val attemptStore = NativeTestSupport.attemptStore()
        val now = System.currentTimeMillis()
        attemptStore.save(
            StoredPendingNativeAuth(
                state = "stale-state",
                codeVerifier = "stale-verifier",
                clientId = NativeTestSupport.OTHER_ANDROID_ID,
                redirectUri = NativeTestSupport.REDIRECT,
                issuedAtEpochMs = now,
                expiresAtEpochMs = now + 1_800_000L,
            ),
        )
        val manager = manager(
            nativeAuth(),
            testStore(),
            attemptStore = attemptStore,
        )
        // Construction resumed the attempt; the gate must end it.
        manager.applyClientGate()
        assertNull(attemptStore.load())
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        assertNull(manager.authorizationLaunch.value)
    }

    @Test
    fun adoptionStampsTheIssuingRegistration() = runBlocking {
        val form = ScriptedFormTransport()
        form.responses.add(
            HttpResponse(
                200,
                """{"access_token":"ya29.granted","expires_in":3599,""" +
                    """"refresh_token":"rt-1","token_type":"Bearer"}""",
            ),
        )
        val store = testStore()
        val manager = manager(GoogleNativeAuth(form, NativeTestSupport.ANDROID_ID), store)

        manager.startSignIn()
        val launch = manager.authorizationLaunch.value!!
        manager.completeSignIn(
            NativeTestSupport.codeRedirect(
                NativeTestSupport.stateFrom(launch.url)
            )
        )
        awaitTrue { manager.uiState.value.signedIn }
        assertEquals("android", store.load()?.clientKind)
        assertEquals(
            NativeTestSupport.ANDROID_ID,
            store.load()?.issuingClientId,
        )
        val body = form.lastFormBody ?: ""
        assertTrue(body.contains("client_id=${NativeTestSupport.ANDROID_ID}"))
        assertFalse(
            "an Android client has no secret to present",
            body.contains("client_secret"),
        )
    }

    @Test
    fun signInIsRefusedHonestlyWhenNoClientIsConfigured() {
        val store = testStore()
        val manager = manager(nativeAuth(clientId = null), store)
        manager.startSignIn()
        val phase = manager.phase.value
        assertTrue(phase is GoogleSignInPhase.Failed)
        assertEquals(
            NOT_CONFIGURED_REASON,
            (phase as GoogleSignInPhase.Failed).reason,
        )
        assertNull(manager.authorizationLaunch.value)
    }

    private companion object {

        // The gate's landing copy, pinned literally: the manager's
        // companion is private, and the wording is a product
        // surface — a change to it should fail here on purpose.
        const val CLIENT_CHANGED_REASON =
            "You were signed out because OffLeash's sign-in " +
                "changed. Sign in again to reconnect your account. " +
                "Your playlists and downloads on this device are " +
                "untouched."
        const val UNCONFIGURED_END_REASON =
            "You were signed out because this build of OffLeash " +
                "has no Google sign-in set up, so the saved sign-in " +
                "could never be used here. Your playlists and " +
                "downloads on this device are untouched."
        const val NOT_CONFIGURED_REASON =
            "Google sign-in isn't set up in this build of " +
                "OffLeash yet, so there's nothing to sign in " +
                "with. Everything else in the app works as normal."

        const val USERINFO_BODY =
            """{"sub":"123","name":"Test Listener",""" +
                """"email":"listener@example.com",""" +
                """"picture":"https://example.com/avatar.png"}"""
    }

    // ---------------------------------------------------------------
    // The gate against work still in flight
    // ---------------------------------------------------------------

    /** A form transport that blocks inside the exchange request. */
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
                """{"access_token":"ya29.late","expires_in":3599,""" +
                    """"refresh_token":"rt-late","token_type":"Bearer"}""",
            )
        }
    }

    @Test
    fun anExchangeInFlightAcrossAGateCallNeverAdopts() = runBlocking {
        // The zombie shape (0.8.7 sweep 1, native form): a return
        // is blocked in its exchange when the gate runs with
        // nothing standing. The gate ends the standing attempt and
        // moves the epoch; the returning grant must land nowhere.
        val form = LatchingFormTransport()
        val attemptStore = NativeTestSupport.attemptStore()
        val store = testStore()
        val manager = manager(
            GoogleNativeAuth(form, NativeTestSupport.ANDROID_ID),
            store,
            attemptStore = attemptStore,
        )

        manager.startSignIn()
        val launch = manager.authorizationLaunch.value!!
        val returning = async(Dispatchers.Default) {
            manager.completeSignIn(
                NativeTestSupport.codeRedirect(
                    NativeTestSupport.stateFrom(launch.url)
                )
            )
        }
        assertTrue(form.entered.await(5, TimeUnit.SECONDS))
        // The caller-established change (the 0.8.7 wizard-save
        // shape): the gate ends the standing attempt outright.
        manager.applyClientGate(forceCleanResign = true)
        form.release.countDown()
        returning.await()
        delay(600)

        assertNull(store.load())
        assertNull(attemptStore.load())
        assertFalse(manager.uiState.value.signedIn)
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
    }
}
