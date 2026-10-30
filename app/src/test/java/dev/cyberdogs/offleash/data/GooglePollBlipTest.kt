package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleDeviceAuth
import dev.cyberdogs.offleash.core.auth.GoogleOAuth
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The 0.8.1 hotfix suite: a transient network blip during the
 * approval window must not end the sign-in watch. Field evidence
 * (the owner's first 0.8.0 attempt, 2026-10-30 13:56): eight clean
 * `poll: still pending` at the 5s cadence, then ONE
 * UnknownHostException — and the flow landed on the terminal
 * failure card, because a transport exception mapped to the same
 * Failed result as a protocol refusal.
 *
 * Pinned here: (a) pending → transport failures → the approval
 * lands and is adopted with no owner action, the phase NEVER
 * becomes Failed, and the waiting state's connection-trouble note
 * shows while the watch rides the blip out (and clears on the next
 * definitive answer); (b) transient failures that run into the
 * grant's expiry end on the honest EXPIRED ending, store cleared;
 * (c) a protocol denial landing mid-blip-sequence ends the flow
 * immediately — blips never mask a terminal answer; (d) the
 * consecutive-transient budget, when genuinely exhausted, ends on
 * the failure card with the code KEPT, and resuming adopts the
 * same code once the connection is back.
 */
class GooglePollBlipTest {

    /** Form transport: issuance counted; polls scripted by [onPoll] (may throw). */
    private class BlipTransport : HttpTransport {
        val deviceCodeRequests = AtomicInteger(0)
        val pollRequests = AtomicInteger(0)

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
                HttpResponse(200, ISSUE_BODY_FAST)
            }
            url == GoogleOAuth.TOKEN_URL && formBody.contains("device_code=") -> {
                pollRequests.incrementAndGet()
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
        form: BlipTransport,
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

    private fun grantedResponse(): HttpResponse = HttpResponse(
        200,
        """{"access_token":"ya29.granted","expires_in":3599,""" +
            """"refresh_token":"rt-1","token_type":"Bearer"}""",
    )

    private fun pendingResponse(): HttpResponse =
        HttpResponse(400, """{"error":"authorization_pending"}""")

    /** Polls [condition] until it holds or [timeoutMs] passes. */
    private suspend fun awaitTrue(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(50)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    /**
     * Keeps waking the parked poll loop (the "check now" signal)
     * until [condition] holds — the test's substitute for the
     * passage of the poll cadence, so backoff parks don't slow it.
     */
    private suspend fun pumpUntil(
        manager: GoogleSessionManager,
        timeoutMs: Long = 25_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            manager.checkNow()
            delay(120)
        }
        fail("pump condition not met within ${timeoutMs}ms")
    }

    @Test
    fun aTransportBlipDuringTheApprovalWindowDoesNotEndTheWatch() = runBlocking {
        val form = BlipTransport()
        val tokenStore = testTokenStore()
        val pendingStore = testPendingStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, tokenStore, pendingStore, scope)

        // Every phase the flow publishes, in order — the failure
        // card must NEVER appear in it.
        val phasesSeen = Collections.synchronizedList(
            mutableListOf<GoogleSignInPhase>()
        )
        val collectJob = launch { manager.phase.collect { phasesSeen.add(it) } }

        // Script: pending, two DNS blips, a poll that BLOCKS until
        // the test has seen the trouble note (so the note's
        // lifetime is observed deterministically, not raced), then
        // pending again (the note must clear), then the approval.
        val poll4Gate = CountDownLatch(1)
        val polls = AtomicInteger(0)
        form.onPoll = {
            when (polls.incrementAndGet()) {
                1 -> pendingResponse()
                2, 3 -> throw UnknownHostException("dns hiccup")
                4 -> {
                    poll4Gate.await(15, TimeUnit.SECONDS)
                    pendingResponse()
                }
                else -> grantedResponse()
            }
        }

        manager.startSignIn()
        awaitTrue { manager.phase.value is GoogleSignInPhase.AwaitingApproval }

        // The blips land: the watch survives, still awaiting, and
        // the waiting state carries the connection-trouble note.
        pumpUntil(manager) {
            val p = manager.phase.value
            p is GoogleSignInPhase.AwaitingApproval && p.connectionTrouble
        }
        assertFalse(manager.uiState.value.signedIn)

        // The connection answers again: the note clears while the
        // code still stands…
        poll4Gate.countDown()
        pumpUntil(manager) {
            val p = manager.phase.value
            p is GoogleSignInPhase.AwaitingApproval && !p.connectionTrouble
        }

        // …and the approval is adopted with no owner action at all.
        pumpUntil(manager) { manager.uiState.value.signedIn }
        assertEquals("ya29.granted", tokenStore.load()!!.accessToken)
        assertEquals(1, form.deviceCodeRequests.get())
        assertNull(pendingStore.load())

        collectJob.cancel()
        assertTrue(
            "the failure card appeared during a survivable blip: $phasesSeen",
            phasesSeen.none { it is GoogleSignInPhase.Failed },
        )
        scope.cancel()
    }

    @Test
    fun transientFailuresUntilExpiryEndOnTheHonestExpiredEnding() = runBlocking {
        val form = BlipTransport()
        form.onPoll = { throw UnknownHostException("network down") }
        val pendingStore = testPendingStore()
        val clock = AtomicLong(1_700_000_000_000L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(
            form,
            testTokenStore(),
            pendingStore,
            scope,
            nowMs = { clock.get() },
        )

        manager.startSignIn()
        awaitTrue { manager.phase.value is GoogleSignInPhase.AwaitingApproval }
        // At least one blip is ridden out (the watch does not die
        // on the first failure)…
        pumpUntil(manager) {
            val p = manager.phase.value
            p is GoogleSignInPhase.AwaitingApproval && p.connectionTrouble
        }

        // …but the grant's own life runs out mid-outage (the fake
        // clock jumps past the 1800s grant): the ending is the
        // honest EXPIRED one, and the record is cleared — a dead
        // code is never kept as a zombie.
        clock.addAndGet(1_801_000L)
        pumpUntil(manager) { manager.phase.value is GoogleSignInPhase.Failed }
        val reason = (manager.phase.value as GoogleSignInPhase.Failed).reason
        assertTrue("expected the expired ending, was: $reason", reason.contains("expired"))
        assertNull(pendingStore.load())
        assertFalse(manager.uiState.value.signedIn)

        // And no zombie polling follows the ending.
        val pollsAtEnd = form.pollRequests.get()
        delay(1_500)
        assertEquals(pollsAtEnd, form.pollRequests.get())
        scope.cancel()
    }

    @Test
    fun deniedLandingMidBlipSequenceEndsTheFlowImmediately() = runBlocking {
        val form = BlipTransport()
        val pendingStore = testPendingStore()
        val polls = AtomicInteger(0)
        form.onPoll = {
            when (polls.incrementAndGet()) {
                1 -> throw UnknownHostException("dns hiccup")
                2 -> HttpResponse(400, """{"error":"access_denied"}""")
                else -> grantedResponse() // must never be reached
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, testTokenStore(), pendingStore, scope)

        manager.startSignIn()
        awaitTrue { manager.phase.value is GoogleSignInPhase.AwaitingApproval }
        pumpUntil(manager) { manager.phase.value is GoogleSignInPhase.Failed }
        val reason = (manager.phase.value as GoogleSignInPhase.Failed).reason
        assertTrue("expected the denied ending, was: $reason", reason.contains("declined"))
        // Terminal means terminal: the record is wiped and the
        // watch stops dead — no further polls, blips or not.
        assertNull(pendingStore.load())
        val pollsAtEnd = form.pollRequests.get()
        manager.checkNow()
        delay(1_500)
        assertEquals(pollsAtEnd, form.pollRequests.get())
        assertFalse(manager.uiState.value.signedIn)
        scope.cancel()
    }

    @Test
    fun exhaustingTheTransientBudgetEndsWithTheCodeKeptAndResumable() = runBlocking {
        val form = BlipTransport()
        form.onPoll = { throw UnknownHostException("network down") }
        val pendingStore = testPendingStore()
        val tokenStore = testTokenStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, tokenStore, pendingStore, scope)

        manager.startSignIn()
        awaitTrue { manager.phase.value is GoogleSignInPhase.AwaitingApproval }

        // 20 consecutive no-answer polls are ridden out; the 21st
        // spends the budget and lands on the failure card…
        pumpUntil(manager, timeoutMs = 60_000) {
            manager.phase.value is GoogleSignInPhase.Failed
        }
        val reason = (manager.phase.value as GoogleSignInPhase.Failed).reason
        assertTrue(
            "expected the connection ending, was: $reason",
            reason.contains("kept failing"),
        )
        // …with the code KEPT (still the originally issued grant —
        // the card's try-again resumes it, it is not burned)…
        assertEquals("dc-1", pendingStore.load()!!.deviceCode)
        assertEquals(1, form.deviceCodeRequests.get())

        // …and once the connection is back, resuming adopts the
        // SAME code's approval. No new issuance, ever.
        form.onPoll = { grantedResponse() }
        manager.startSignIn()
        awaitTrue { manager.uiState.value.signedIn }
        assertEquals("ya29.granted", tokenStore.load()!!.accessToken)
        assertEquals(1, form.deviceCodeRequests.get())
        assertNull(pendingStore.load())
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
