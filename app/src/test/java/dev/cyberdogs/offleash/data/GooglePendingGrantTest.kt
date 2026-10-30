package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleDeviceAuth
import dev.cyberdogs.offleash.core.auth.GoogleOAuth
import java.security.InvalidAlgorithmParameterException
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
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
 * The 0.7.1 sign-in survival suite: the pending device grant is
 * durable app-level state, not screen state. Field report it pins
 * shut (2026-10-27): "Right as soon as I click out of the app the
 * sign in process restarts" — in 0.7.0 the grant lived only as a
 * local variable in the poll coroutine, so leaving for the browser
 * (and the process death that can follow) orphaned the code the
 * owner was approving.
 *
 * Covered: (a) re-entering sign-in never issues a second grant —
 * the same code is rendered throughout; (b) a fresh manager over
 * the same stores (process death + relaunch) resumes polling the
 * PERSISTED grant and adopts its approval; (c) a persisted expired
 * grant is cleared with the plain expired ending and no zombie
 * polling; (d) "check now" polls the current grant immediately,
 * without waiting out its interval and without a new issuance.
 */
class GooglePendingGrantTest {

    /**
     * Form-POST transport routed by endpoint + body: device-code
     * issuance is counted and answered from [issueBody]; token
     * polls are counted, record the device code they carried, and
     * are answered by [onPoll]. Everything else is refused.
     */
    private class RoutingFormTransport(
        private val issueBody: String = ISSUE_BODY_FAST,
    ) : HttpTransport {
        val deviceCodeRequests = AtomicInteger(0)
        val pollRequests = AtomicInteger(0)

        @Volatile
        var lastPolledDeviceCode: String? = null
            private set

        @Volatile
        var onPoll: () -> HttpResponse = {
            HttpResponse(400, """{"error":"authorization_pending"}""")
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
                HttpResponse(200, issueBody)
            }
            url == GoogleOAuth.TOKEN_URL && formBody.contains("device_code=") -> {
                pollRequests.incrementAndGet()
                lastPolledDeviceCode = formBody
                    .substringAfter("device_code=")
                    .substringBefore("&")
                onPoll()
            }
            else -> throw AssertionError("unexpected form POST: $url")
        }
    }

    /** Transport whose GET answers the userinfo endpoint; POSTs are refused. */
    private class UserinfoTransport : HttpTransport {
        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse = throw UnsupportedOperationException("GET only")

        override fun get(url: String, headers: Map<String, String>): HttpResponse =
            HttpResponse(200, USERINFO_BODY)
    }

    private fun manager(
        form: RoutingFormTransport,
        tokenStore: GoogleTokenStore,
        pendingStore: GooglePendingGrantStore,
        scope: CoroutineScope,
        nowMs: () -> Long = { System.currentTimeMillis() },
    ): GoogleSessionManager = GoogleSessionManager(
        store = tokenStore,
        auth = GoogleDeviceAuth(form),
        client = YoutubeiClient(UserinfoTransport()),
        scope = scope,
        pendingGrantStore = pendingStore,
        signInDiagnostics = SignInDiagnostics(),
        nowMs = nowMs,
    )

    private fun testTokenStore(): GoogleTokenStore =
        GoogleTokenStore(InMemorySecretStorage(), testSecretBox())

    private fun testPendingStore(): GooglePendingGrantStore =
        GooglePendingGrantStore(InMemorySecretStorage(), testSecretBox())

    /**
     * A key source standing in for the owner's 0.8.0 device: the
     * standing key's parameters refuse every seal
     * (InvalidAlgorithmParameterException out of the seal attempt,
     * as the Android Keystore provider throws from Cipher.init)
     * until the box's scoped regeneration "rebuilds" it (0.8.1
     * hotfix). Counts regenerations.
     */
    private class RecoveringKeySource {
        val realKey: SecretKey = testSecretKey()
        var regenerated = false
        var regenerations = 0

        fun key(): SecretKey {
            if (!regenerated) {
                throw InvalidAlgorithmParameterException("caller IV refused by key spec")
            }
            return realKey
        }

        fun regenerate(): SecretKey {
            regenerations++
            regenerated = true
            return realKey
        }
    }

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
    fun reenteringSignInNeverIssuesASecondGrant() = runBlocking {
        val form = RoutingFormTransport()
        val tokenStore = testTokenStore()
        val pendingStore = testPendingStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, tokenStore, pendingStore, scope)

        manager.startSignIn()
        awaitTrue { manager.phase.value is GoogleSignInPhase.AwaitingApproval }
        val first = manager.phase.value as GoogleSignInPhase.AwaitingApproval
        assertEquals("WDJB-MJHT", first.userCode)
        assertEquals(1, form.deviceCodeRequests.get())
        // The grant was persisted the moment it was issued.
        assertEquals("dc-1", pendingStore.load()!!.deviceCode)

        // The screen is destroyed and recreated. It only ever reads
        // state — but pin the invariant at the layer the bug lived
        // in: even an explicit fresh start, and a check-now, must
        // resume THIS grant, never issue another.
        manager.startSignIn()
        manager.checkNow()
        awaitTrue { form.pollRequests.get() >= 1 }
        delay(300)
        val again = manager.phase.value as GoogleSignInPhase.AwaitingApproval
        assertEquals(first.userCode, again.userCode)
        assertEquals(first.expiresAtEpochMs, again.expiresAtEpochMs)
        assertEquals(1, form.deviceCodeRequests.get())
        assertEquals("dc-1", form.lastPolledDeviceCode)

        manager.cancelSignIn()
        // Cancel abandons the grant everywhere — it can never resume.
        assertNull(pendingStore.load())
        scope.cancel()
    }

    @Test
    fun aFreshManagerResumesThePersistedGrantAndAdoptsItsApproval() = runBlocking {
        val form = RoutingFormTransport()
        val tokenStore = testTokenStore()
        val pendingStore = testPendingStore()

        // First "process": a grant is issued and left unapproved.
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = manager(form, tokenStore, pendingStore, scope1)
        first.startSignIn()
        awaitTrue { first.phase.value is GoogleSignInPhase.AwaitingApproval }
        assertEquals(1, form.deviceCodeRequests.get())

        // Process death: the manager's whole scope dies with it.
        scope1.cancel()
        delay(250) // let the parked poll loop observe the cancellation

        // While the app is dead, the owner approves the ORIGINAL code.
        form.onPoll = { grantedResponse() }

        // Relaunch: a brand-new manager over the same stores. It
        // republishes the same code from the sealed record alone —
        // no second issuance — and its resumed polling collects the
        // approval the owner already gave.
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val second = manager(form, tokenStore, pendingStore, scope2)
        val phase = second.phase.value
        assertTrue(phase is GoogleSignInPhase.AwaitingApproval)
        assertEquals(
            "WDJB-MJHT",
            (phase as GoogleSignInPhase.AwaitingApproval).userCode,
        )
        assertEquals(1, form.deviceCodeRequests.get())

        awaitTrue { second.uiState.value.signedIn }
        assertEquals("dc-1", form.lastPolledDeviceCode)
        assertEquals("ya29.granted", tokenStore.load()!!.accessToken)
        // The spent grant is gone from the store — nothing to resume.
        assertNull(pendingStore.load())
        scope2.cancel()
    }

    @Test
    fun aGrantSealedThroughKeyRecoveryIsResumedByAFreshManager() = runBlocking {
        // The owner's 0.8.0 field failure, pinned (0.8.1 hotfix):
        // on his phone the pending-grant seal threw
        // InvalidAlgorithmParameterException from the Keystore
        // path, totality degraded the grant to memory-only, and
        // 0.7.1's durability did not exist on his device. With the
        // box's scoped recovery, the SAME save path now regenerates
        // the key once, the seal persists, and a fresh manager
        // resumes the same code.
        val form = RoutingFormTransport()
        val source = RecoveringKeySource()
        val box = AesGcmSecretBox(source::key, source::regenerate)
        val pendingStore = GooglePendingGrantStore(InMemorySecretStorage(), box)
        val tokenStore = testTokenStore()

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = manager(form, tokenStore, pendingStore, scope1)
        first.startSignIn()
        awaitTrue { first.phase.value is GoogleSignInPhase.AwaitingApproval }

        // Exactly one regeneration healed the seal, and the grant
        // IS persisted — readable back through the same box.
        assertEquals(1, source.regenerations)
        assertEquals("dc-1", pendingStore.load()!!.deviceCode)

        // Process death, then the owner's approval of the original
        // code lands while the app is dead.
        scope1.cancel()
        delay(250)
        form.onPoll = { grantedResponse() }

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val second = manager(form, tokenStore, pendingStore, scope2)
        val phase = second.phase.value
        assertTrue(phase is GoogleSignInPhase.AwaitingApproval)
        assertEquals(
            "WDJB-MJHT",
            (phase as GoogleSignInPhase.AwaitingApproval).userCode,
        )
        assertEquals(1, form.deviceCodeRequests.get())

        awaitTrue { second.uiState.value.signedIn }
        assertEquals("dc-1", form.lastPolledDeviceCode)
        assertEquals("ya29.granted", tokenStore.load()!!.accessToken)
        assertNull(pendingStore.load())
        // Still exactly one: resuming and adopting never needed a
        // second regeneration.
        assertEquals(1, source.regenerations)
        scope2.cancel()
    }

    @Test
    fun anExpiredPersistedGrantIsClearedAndNeverPolled() = runBlocking {
        val form = RoutingFormTransport()
        val pendingStore = testPendingStore()
        val now = 1_700_000_000_000L
        pendingStore.save(
            StoredPendingGrant(
                deviceCode = "dc-dead",
                userCode = "DEAD-CODE",
                verificationUrl = "https://www.google.com/device",
                issuedAtEpochMs = now - 1_800_000L,
                expiresAtEpochMs = now - 1_000L,
                intervalSeconds = 5L,
            )
        )

        val manager = manager(
            form,
            testTokenStore(),
            pendingStore,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            nowMs = { now },
        )

        // Cleared at construction; the screen lands on the plain
        // expired ending (whose card carries the fresh-start button).
        assertNull(pendingStore.load())
        val phase = manager.phase.value
        assertTrue(phase is GoogleSignInPhase.Failed)
        assertTrue(
            (phase as GoogleSignInPhase.Failed).reason.contains("expired")
        )
        assertFalse(manager.uiState.value.signedIn)

        // No issuance and no polling — not now, not after a beat:
        // an expired grant never becomes a zombie.
        delay(1_200)
        assertEquals(0, form.deviceCodeRequests.get())
        assertEquals(0, form.pollRequests.get())
    }

    @Test
    fun checkNowPollsTheCurrentGrantImmediately() = runBlocking {
        val form = RoutingFormTransport(issueBody = ISSUE_BODY_LONG_INTERVAL)
        form.onPoll = { grantedResponse() }
        val tokenStore = testTokenStore()
        val pendingStore = testPendingStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, tokenStore, pendingStore, scope)

        manager.startSignIn()
        awaitTrue { manager.phase.value is GoogleSignInPhase.AwaitingApproval }
        assertEquals("LNGW-AIT1", (
            manager.phase.value as GoogleSignInPhase.AwaitingApproval
            ).userCode
        )

        // The poll loop is parked for the grant's hour-long
        // interval; check-now must poll THIS grant at once.
        manager.checkNow()
        awaitTrue(timeoutMs = 10_000) { manager.uiState.value.signedIn }
        assertEquals(1, form.deviceCodeRequests.get())
        assertEquals("dc-long", form.lastPolledDeviceCode)
        assertEquals("ya29.granted", tokenStore.load()!!.accessToken)
        assertNull(pendingStore.load())
        scope.cancel()
    }

    private companion object {
        /** interval 0: the manager floors it at 1s of poll cadence. */
        const val ISSUE_BODY_FAST =
            """{"device_code":"dc-1","user_code":"WDJB-MJHT",""" +
                """"verification_url":"https://www.google.com/device",""" +
                """"expires_in":1800,"interval":0}"""

        /** A grant whose poll interval is an hour: only a wake-up polls it. */
        const val ISSUE_BODY_LONG_INTERVAL =
            """{"device_code":"dc-long","user_code":"LNGW-AIT1",""" +
                """"verification_url":"https://www.google.com/device",""" +
                """"expires_in":1800,"interval":3600}"""

        const val USERINFO_BODY =
            """{"sub":"123","name":"Test Listener",""" +
                """"email":"listener@example.com",""" +
                """"picture":"https://example.com/avatar.png"}"""
    }
}
