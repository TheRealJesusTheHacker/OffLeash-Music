package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleDeviceAuth
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
 * The 0.7.2 full-sweep lifecycle suite for the Google sign-in:
 * the races and honest-state edges the 0.7.1 grant-durability
 * suite did not cover.
 *
 * - a double-tap DURING issuance must not cancel-and-reissue
 *   (the cancelled attempt's code still minted at Google, and
 *   nobody would ever poll it);
 * - sign-out while a grant is pending wipes the record and stops
 *   the poll loop;
 * - sign-out racing an adoption's final save is total — the
 *   adoption cannot re-store or re-publish behind its back;
 * - an expired token with NO refresh token ends the session
 *   honestly instead of masquerading as signed in forever;
 * - a TRANSIENT refresh failure never wipes the session, and a
 *   not-yet-expired token is still served;
 * - concurrent bearer calls share ONE refresh (single-flight);
 * - a slow_down cadence is persisted with the grant, so a restart
 *   does not resume at the cadence Google just refused;
 * - a grant with a long poll interval still expires PROMPTLY —
 *   the park is capped at its remaining life;
 * - a leftover grant beside a stored session is stale at restore;
 * - identity answers that carry an email but no name never store
 *   a blank name, and a partial identity refresh never erases the
 *   name/avatar the store already knows;
 * - a first-save failure at adoption lands on the store card and
 *   stores nothing (the Google road's H3 corridor).
 */
class GoogleLifecycleSweepTest {

    /** Form-POST transport routed by endpoint + body, all counted. */
    private class SweepFormTransport : HttpTransport {
        val deviceCodeRequests = AtomicInteger(0)
        val pollRequests = AtomicInteger(0)
        val refreshRequests = AtomicInteger(0)
        val revokeRequests = AtomicInteger(0)

        @Volatile
        var issueBody: String = ISSUE_BODY_FAST

        @Volatile
        var issueGate: CountDownLatch? = null

        @Volatile
        var onPoll: () -> HttpResponse = {
            HttpResponse(400, """{"error":"authorization_pending"}""")
        }

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
            url == GoogleOAuth.DEVICE_CODE_URL -> {
                deviceCodeRequests.incrementAndGet()
                issueGate?.await(10, TimeUnit.SECONDS)
                HttpResponse(200, issueBody)
            }
            url == GoogleOAuth.TOKEN_URL && formBody.contains("device_code=") -> {
                pollRequests.incrementAndGet()
                onPoll()
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
        pendingStore: GooglePendingGrantStore? = GooglePendingGrantStore(
            InMemorySecretStorage(),
            testSecretBox(),
        ),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        userinfoBody: String = USERINFO_BODY,
        nowMs: () -> Long = { System.currentTimeMillis() },
    ): GoogleSessionManager = GoogleSessionManager(
        store = tokenStore,
        auth = GoogleDeviceAuth(form),
        client = YoutubeiClient(UserinfoTransport(body = userinfoBody)),
        scope = scope,
        pendingGrantStore = pendingStore,
        signInDiagnostics = SignInDiagnostics(),
        nowMs = nowMs,
    )

    private fun testTokenStore(): GoogleTokenStore =
        GoogleTokenStore(InMemorySecretStorage(), testSecretBox())

    private fun grantedResponse(): HttpResponse = HttpResponse(
        200,
        """{"access_token":"ya29.granted","expires_in":3599,""" +
            """"refresh_token":"rt-1","token_type":"Bearer"}""",
    )

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
    fun doubleTapDuringIssuanceIssuesOnlyOneGrant() = runBlocking {
        val form = SweepFormTransport()
        form.issueGate = CountDownLatch(1)
        val pendingStore = GooglePendingGrantStore(InMemorySecretStorage(), testSecretBox())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, testTokenStore(), pendingStore, scope)

        manager.startSignIn()
        awaitTrue { form.deviceCodeRequests.get() >= 1 }
        // The issuance is parked at Google. A second (and third)
        // tap — plus a check-now — must JOIN it, not restart it.
        manager.startSignIn()
        manager.startSignIn()
        manager.checkNow()
        delay(400)
        assertEquals(
            "a second tap during issuance must not re-issue",
            1,
            form.deviceCodeRequests.get(),
        )

        form.issueGate!!.countDown()
        awaitTrue { manager.phase.value is GoogleSignInPhase.AwaitingApproval }
        assertEquals(1, form.deviceCodeRequests.get())
        assertEquals("dc-1", pendingStore.load()!!.deviceCode)
        manager.cancelSignIn()
        scope.cancel()
    }

    @Test
    fun checkNowWithNoGrantIssuesAndPollsNothing() = runBlocking {
        val form = SweepFormTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, testTokenStore(), scope = scope)

        manager.checkNow()
        delay(400)
        assertEquals(0, form.deviceCodeRequests.get())
        assertEquals(0, form.pollRequests.get())
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        scope.cancel()
    }

    @Test
    fun signOutWhileGrantIsPendingWipesItAndStopsPolling() = runBlocking {
        val form = SweepFormTransport()
        val pendingStore = GooglePendingGrantStore(InMemorySecretStorage(), testSecretBox())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, testTokenStore(), pendingStore, scope)

        manager.startSignIn()
        awaitTrue { manager.phase.value is GoogleSignInPhase.AwaitingApproval }
        assertTrue(pendingStore.load() != null)

        manager.signOut()

        assertNull("sign-out must wipe a pending grant", pendingStore.load())
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        assertFalse(manager.uiState.value.signedIn)
        delay(300) // let any in-flight poll land before counting
        val pollsAtSignOut = form.pollRequests.get()
        delay(2_500)
        assertEquals(
            "no poll may run after sign-out",
            pollsAtSignOut,
            form.pollRequests.get(),
        )
        scope.cancel()
    }

    @Test
    fun signOutRacingTheAdoptionSaveIsTotal() = runBlocking {
        val form = SweepFormTransport()
        form.onPoll = { grantedResponse() }
        val blockingStorage = BlockingFirstWriteStorage()
        val tokenStore = GoogleTokenStore(blockingStorage, testSecretBox())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, tokenStore, scope = scope)

        manager.startSignIn()
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
    fun slowDownCadenceIsPersistedWithTheGrant() = runBlocking {
        val form = SweepFormTransport()
        val polls = AtomicInteger(0)
        form.onPoll = {
            if (polls.incrementAndGet() == 1) {
                HttpResponse(400, """{"error":"slow_down"}""")
            } else {
                HttpResponse(400, """{"error":"authorization_pending"}""")
            }
        }
        val pendingStore = GooglePendingGrantStore(InMemorySecretStorage(), testSecretBox())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, testTokenStore(), pendingStore, scope)

        manager.startSignIn()
        awaitTrue { manager.phase.value is GoogleSignInPhase.AwaitingApproval }
        // Issued with interval 0; Google's slow_down adds 5s to the
        // floored 1s cadence — the persisted record must say 6, so
        // a restart resumes at the cadence Google asked for.
        awaitTrue { pendingStore.load()?.intervalSeconds == 6L }
        manager.cancelSignIn()
        scope.cancel()
    }

    @Test
    fun longIntervalGrantStillExpiresPromptly() = runBlocking {
        val form = SweepFormTransport()
        form.issueBody =
            """{"device_code":"dc-short","user_code":"SHRT-LIFE",""" +
                """"verification_url":"https://www.google.com/device",""" +
                """"expires_in":2,"interval":3600}"""
        val pendingStore = GooglePendingGrantStore(InMemorySecretStorage(), testSecretBox())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, testTokenStore(), pendingStore, scope)

        manager.startSignIn()
        awaitTrue(timeoutMs = 10_000) {
            manager.phase.value is GoogleSignInPhase.Failed
        }
        val failed = manager.phase.value as GoogleSignInPhase.Failed
        assertTrue(failed.reason.contains("expired"))
        assertEquals("an expired grant is never polled", 0, form.pollRequests.get())
        assertNull(pendingStore.load())
        scope.cancel()
    }

    @Test
    fun aStoredSessionMakesALeftoverGrantStaleAtRestore() = runBlocking {
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
        val pendingStore = GooglePendingGrantStore(InMemorySecretStorage(), testSecretBox())
        val now = System.currentTimeMillis()
        pendingStore.save(
            StoredPendingGrant(
                deviceCode = "dc-leftover",
                userCode = "LEFT-OVER",
                verificationUrl = "https://www.google.com/device",
                issuedAtEpochMs = now,
                expiresAtEpochMs = now + 1_800_000L,
                intervalSeconds = 5L,
            )
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, tokenStore, pendingStore, scope)

        assertTrue(manager.uiState.value.signedIn)
        assertNull("a leftover grant beside a session is wiped", pendingStore.load())
        delay(1_200)
        assertEquals(0, form.deviceCodeRequests.get())
        assertEquals(0, form.pollRequests.get())
        scope.cancel()
    }

    @Test
    fun adoptionOfAnEmailOnlyIdentityStoresNoBlankName() = runBlocking {
        val form = SweepFormTransport()
        form.onPoll = { grantedResponse() }
        val tokenStore = testTokenStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(
            form,
            tokenStore,
            scope = scope,
            userinfoBody = """{"sub":"123","email":"listener@example.com"}""",
        )

        manager.startSignIn()
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
        form.onPoll = { grantedResponse() }
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

        manager.startSignIn()
        awaitTrue { manager.phase.value is GoogleSignInPhase.Failed }
        val failed = manager.phase.value as GoogleSignInPhase.Failed
        assertTrue(failed.reason.contains("couldn't save the sign-in"))
        assertFalse(manager.uiState.value.signedIn)
        assertNull(tokenStore.load())
        scope.cancel()
    }

    private companion object {
        /** interval 0: the manager floors it at 1s of poll cadence. */
        const val ISSUE_BODY_FAST =
            """{"device_code":"dc-1","user_code":"WDJB-MJHT",""" +
                """"verification_url":"https://www.google.com/device",""" +
                """"expires_in":1800,"interval":0}"""

        const val USERINFO_BODY =
            """{"sub":"123","name":"Test Listener",""" +
                """"email":"listener@example.com",""" +
                """"picture":"https://example.com/avatar.png"}"""
    }
}
