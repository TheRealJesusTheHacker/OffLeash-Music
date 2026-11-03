package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleNativeAuth
import dev.cyberdogs.offleash.core.auth.GoogleOAuth
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 * The full-sweep lifecycle suite for the Google sign-in, carried
 * to the native flow (0.8.8): the races and honest-state edges of
 * the session's life.
 *
 * - a double-tap while an attempt stands joins it — the same
 *   attempt, never a second one minted over it;
 * - sign-out while an attempt is pending wipes the record;
 * - sign-out racing an adoption's final save is total — the
 *   adoption cannot re-store or re-publish behind its back;
 * - an expired token with NO refresh token ends the session
 *   honestly instead of masquerading as signed in forever;
 * - a TRANSIENT refresh failure never wipes the session, and a
 *   not-yet-expired token is still served;
 * - concurrent bearer calls share ONE refresh (single-flight);
 * - a leftover attempt beside a stored session is stale at
 *   restore and is wiped, never resumed;
 * - identity answers that carry an email but no name never store
 *   a blank name, and a partial identity refresh never erases the
 *   name/avatar the store already knows;
 * - a first-save failure at adoption lands on the store card and
 *   stores nothing (the Google road's H3 corridor).
 */
class GoogleLifecycleSweepTest {

    /** Form-POST transport routed by endpoint + body, all counted. */
    private class SweepFormTransport : HttpTransport {
        val exchangeRequests = AtomicInteger(0)
        val refreshRequests = AtomicInteger(0)
        val revokeRequests = AtomicInteger(0)

        @Volatile
        var onExchange: () -> HttpResponse = { grantedResponse() }

        @Volatile
        var onRefresh: () -> HttpResponse = {
            HttpResponse(
                200,
                """{"access_token":"ya29.fresh","expires_in":3599,"token_type":"Bearer"}""",
            )
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
    }

    /** GET transport answering userinfo with a scripted body. */
    private class UserinfoTransport(
        @Volatile var body: String = USERINFO_BODY,
        private val status: Int = 200,
    ) : HttpTransport {
        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse = throw UnsupportedOperationException("GET only")

        override fun get(url: String, headers: Map<String, String>): HttpResponse =
            HttpResponse(status, body)
    }

    /** Storage whose first write parks until the test releases it. */
    private class BlockingFirstWriteStorage : SecretStorage {
        @Volatile
        private var bytes: ByteArray? = null
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        private val writes = AtomicInteger(0)

        override fun read(): ByteArray? = bytes?.copyOf()

        override fun write(bytes: ByteArray) {
            if (writes.incrementAndGet() == 1) {
                writeEntered.countDown()
                releaseWrite.await(10, TimeUnit.SECONDS)
            }
            this.bytes = bytes.copyOf()
        }

        override fun clear() {
            bytes = null
        }
    }

    private fun manager(
        form: SweepFormTransport,
        tokenStore: GoogleTokenStore,
        attemptStore: GooglePendingNativeAuthStore? =
            NativeTestSupport.attemptStore(),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        userinfoBody: String = USERINFO_BODY,
        nowMs: () -> Long = { System.currentTimeMillis() },
    ): GoogleSessionManager = GoogleSessionManager(
        store = tokenStore,
        auth = GoogleNativeAuth(form, NativeTestSupport.ANDROID_ID),
        client = YoutubeiClient(UserinfoTransport(body = userinfoBody)),
        scope = scope,
        redirectUri = NativeTestSupport.REDIRECT,
        pendingAttemptStore = attemptStore,
        signInDiagnostics = SignInDiagnostics(),
        nowMs = nowMs,
    )

    private fun testTokenStore(): GoogleTokenStore =
        GoogleTokenStore(InMemorySecretStorage(), testSecretBox())

    /** Drives a native sign-in from launch to the code's return. */
    private fun signIn(manager: GoogleSessionManager) {
        manager.startSignIn()
        val launch = manager.authorizationLaunch.value
        assertTrue("a launch must be published", launch != null)
        manager.completeSignIn(
            NativeTestSupport.codeRedirect(
                NativeTestSupport.stateFrom(launch!!.url)
            )
        )
    }

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
    fun doubleTapWhileAnAttemptStandsJoinsIt() = runBlocking {
        val form = SweepFormTransport()
        val attemptStore = NativeTestSupport.attemptStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, testTokenStore(), attemptStore, scope)

        manager.startSignIn()
        val first = manager.authorizationLaunch.value!!
        // A second (and third) tap while the attempt stands must
        // JOIN it — the browser may already be open on it, and a
        // fresh attempt would orphan the approval in flight.
        manager.startSignIn()
        manager.startSignIn()
        assertEquals(first.attemptId, manager.authorizationLaunch.value?.attemptId)
        assertEquals(
            NativeTestSupport.stateFrom(first.url),
            NativeTestSupport.stateFrom(manager.authorizationLaunch.value!!.url),
        )
        assertEquals(
            NativeTestSupport.stateFrom(first.url),
            attemptStore.load()?.state,
        )
        manager.cancelSignIn()
        scope.cancel()
    }

    @Test
    fun signOutWhileAnAttemptIsPendingWipesIt() = runBlocking {
        val form = SweepFormTransport()
        val attemptStore = NativeTestSupport.attemptStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, testTokenStore(), attemptStore, scope)

        manager.startSignIn()
        awaitTrue { manager.phase.value is GoogleSignInPhase.AwaitingReturn }
        assertTrue(attemptStore.load() != null)

        manager.signOut()

        assertNull("sign-out must wipe a pending attempt", attemptStore.load())
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        assertFalse(manager.uiState.value.signedIn)
        scope.cancel()
    }

    @Test
    fun signOutRacingTheAdoptionSaveIsTotal() = runBlocking {
        val form = SweepFormTransport()
        val blockingStorage = BlockingFirstWriteStorage()
        val tokenStore = GoogleTokenStore(blockingStorage, testSecretBox())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, tokenStore, scope = scope)

        signIn(manager)
        // The adoption is parked INSIDE the token store's first
        // write — its final, non-suspending stretch.
        assertTrue(
            "adoption must reach the store write",
            blockingStorage.writeEntered.await(15, TimeUnit.SECONDS),
        )
        val signOut = async(Dispatchers.Default) { manager.signOut() }
        delay(400) // sign-out reaches its locked step / waits there
        blockingStorage.releaseWrite.countDown()
        signOut.await()

        assertFalse(
            "the adoption must not resurrect a signed-in state",
            manager.uiState.value.signedIn,
        )
        assertNull("the adoption must not leave tokens stored", tokenStore.load())
        assertNull(manager.currentBearer())
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        scope.cancel()
    }

    @Test
    fun expiredTokenWithoutARefreshTokenEndsTheSessionHonestly() {
        val form = SweepFormTransport()
        val store = testTokenStore()
        store.save(
            StoredGoogleSession(
                accessToken = "ya29.dead",
                refreshToken = null,
                accessTokenExpiresAtEpochMs = 1L,
                identityName = "Test Listener",
            )
        )
        val manager = manager(form, store)

        assertNull(manager.currentBearer())
        assertTrue("the unrefreshable session must be wiped", store.isWipedClean())
        assertFalse(manager.uiState.value.signedIn)
        assertEquals(SessionHealth.NEEDS_SIGN_IN, manager.uiState.value.health)
        assertTrue(
            manager.uiState.value.lastEndReason!!.contains("Sign in again")
        )
        assertEquals(0, form.refreshRequests.get())
    }

    @Test
    fun transientRefreshFailureKeepsTheSession() {
        val form = SweepFormTransport()
        form.onRefresh = {
            HttpResponse(503, """{"error":"temporarily_unavailable"}""")
        }
        val store = testTokenStore()
        store.save(
            StoredGoogleSession(
                accessToken = "ya29.stale",
                refreshToken = "rt-1",
                accessTokenExpiresAtEpochMs = 1L,
            )
        )
        val manager = manager(form, store)

        // Expired + unreachable refresh: no token to serve, but the
        // session itself is NOT revoked — it must survive intact.
        assertNull(manager.currentBearer())
        assertEquals("ya29.stale", store.load()!!.accessToken)
        assertTrue(manager.uiState.value.signedIn)
        assertFalse(
            manager.uiState.value.health == SessionHealth.NEEDS_SIGN_IN
        )
    }

    @Test
    fun transientRefreshFailureStillServesAnUnexpiredToken() {
        val form = SweepFormTransport()
        form.onRefresh = {
            HttpResponse(503, """{"error":"temporarily_unavailable"}""")
        }
        val store = testTokenStore()
        store.save(
            StoredGoogleSession(
                accessToken = "ya29.still-good",
                refreshToken = "rt-1",
                // Inside the 60s skew (so a refresh is attempted)
                // but NOT yet expired.
                accessTokenExpiresAtEpochMs = System.currentTimeMillis() + 30_000L,
            )
        )
        val manager = manager(form, store)

        assertEquals("ya29.still-good", manager.currentBearer())
        assertEquals(1, form.refreshRequests.get())
        assertTrue(manager.uiState.value.signedIn)
    }

    @Test
    fun concurrentBearerCallsShareOneRefresh() {
        val form = SweepFormTransport()
        form.onRefresh = {
            Thread.sleep(400)
            HttpResponse(
                200,
                """{"access_token":"ya29.fresh","expires_in":3599,"token_type":"Bearer"}""",
            )
        }
        val store = testTokenStore()
        store.save(
            StoredGoogleSession(
                accessToken = "ya29.stale",
                refreshToken = "rt-1",
                accessTokenExpiresAtEpochMs = 1L,
            )
        )
        val manager = manager(form, store)

        val startGate = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<String?>()
        val threads = (1..8).map {
            Thread {
                startGate.await(5, TimeUnit.SECONDS)
                results.add(manager.currentBearer())
            }
        }
        threads.forEach { it.start() }
        startGate.countDown()
        threads.forEach { it.join(15_000) }

        assertEquals(8, results.size)
        assertTrue(results.all { it == "ya29.fresh" })
        assertEquals(
            "eight concurrent callers, one refresh on the wire",
            1,
            form.refreshRequests.get(),
        )
    }

    @Test
    fun aStoredSessionMakesALeftoverAttemptStaleAtRestore() = runBlocking {
        val form = SweepFormTransport()
        val tokenStore = testTokenStore()
        tokenStore.save(
            StoredGoogleSession(
                accessToken = "ya29.live",
                refreshToken = "rt-live",
                accessTokenExpiresAtEpochMs = Long.MAX_VALUE,
                identityName = "Test Listener",
            )
        )
        val attemptStore = NativeTestSupport.attemptStore()
        val now = System.currentTimeMillis()
        attemptStore.save(
            StoredPendingNativeAuth(
                state = "leftover-state",
                codeVerifier = "leftover-verifier",
                clientId = NativeTestSupport.ANDROID_ID,
                redirectUri = NativeTestSupport.REDIRECT,
                issuedAtEpochMs = now,
                expiresAtEpochMs = now + 1_800_000L,
            )
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, tokenStore, attemptStore, scope)

        assertTrue(manager.uiState.value.signedIn)
        assertNull(
            "a leftover attempt beside a session is wiped",
            attemptStore.load(),
        )
        assertNull(manager.authorizationLaunch.value)
        assertEquals(0, form.exchangeRequests.get())
        scope.cancel()
    }

    @Test
    fun adoptionOfAnEmailOnlyIdentityStoresNoBlankName() = runBlocking {
        val form = SweepFormTransport()
        val tokenStore = testTokenStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(
            form,
            tokenStore,
            scope = scope,
            userinfoBody = """{"sub":"123","email":"listener@example.com"}""",
        )

        signIn(manager)
        awaitTrue { manager.uiState.value.signedIn }
        val stored = tokenStore.load()!!
        assertNull("a blank name is not a name", stored.identityName)
        assertEquals("listener@example.com", stored.identityEmail)
        assertNull(manager.uiState.value.identityName)
        scope.cancel()
    }

    @Test
    fun partialIdentityRefreshPreservesTheStoredNameAndAvatar() = runBlocking {
        val form = SweepFormTransport()
        val tokenStore = testTokenStore()
        tokenStore.save(
            StoredGoogleSession(
                accessToken = "ya29.live",
                refreshToken = "rt-live",
                accessTokenExpiresAtEpochMs = Long.MAX_VALUE,
                identityName = "Test Listener",
                identityEmail = "listener@example.com",
                avatarUrl = "https://example.com/avatar.png",
            )
        )
        val manager = manager(
            form,
            tokenStore,
            userinfoBody = """{"sub":"123","email":"listener@example.com"}""",
        )

        manager.refreshIdentity()

        val stored = tokenStore.load()!!
        assertEquals("Test Listener", stored.identityName)
        assertEquals("https://example.com/avatar.png", stored.avatarUrl)
        assertEquals(SessionHealth.HEALTHY, manager.uiState.value.health)
    }

    @Test
    fun adoptionSaveFailureEndsOnTheStoreCardAndStoresNothing() = runBlocking {
        val form = SweepFormTransport()
        val throwingWriteStorage = object : SecretStorage {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) {
                throw RuntimeException("simulated storage failure")
            }
            override fun clear() {}
        }
        val tokenStore = GoogleTokenStore(throwingWriteStorage, testSecretBox())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, tokenStore, scope = scope)

        signIn(manager)
        awaitTrue { manager.phase.value is GoogleSignInPhase.Failed }
        val failed = manager.phase.value as GoogleSignInPhase.Failed
        assertTrue(failed.reason.contains("couldn't save"))
        assertFalse(manager.uiState.value.signedIn)
        assertNull(tokenStore.load())
        scope.cancel()
    }

    private companion object {
        fun grantedResponse(): HttpResponse = HttpResponse(
            200,
            """{"access_token":"ya29.granted","expires_in":3599,""" +
                """"refresh_token":"rt-1","token_type":"Bearer"}""",
        )

        const val USERINFO_BODY =
            """{"sub":"123","name":"Test Listener",""" +
                """"email":"listener@example.com",""" +
                """"picture":"https://example.com/avatar.png"}"""
    }
}
