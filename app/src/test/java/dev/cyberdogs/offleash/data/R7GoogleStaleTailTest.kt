package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleDeviceAuth
import dev.cyberdogs.offleash.core.auth.GoogleOAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Round 7 (break the round-6 epoch fix): the sign-in flow's tails.
 *
 * Round 6 taught [GoogleSessionManager.refreshIdentity] to refuse
 * stale outcomes, but the device-code flow itself still applied
 * two tails with no epoch check at all:
 *
 * 1. [GoogleSessionManager.runSignIn] assigned and PERSISTED the
 *    issued grant before its epoch check (which guarded only the
 *    publication). A cancel landing while the code was being
 *    issued had just wiped the persisted grant — cancelSignIn's
 *    contract is "a cancelled code is never resumed later" — and
 *    the returning issuance then re-saved the grant anyway: the
 *    cancelled code was republished as AwaitingApproval, and a
 *    fresh manager after process death resumed polling it.
 *
 * 2. A FAILED issuance (and the poll loop's terminal outcomes)
 *    published their Failed phase unconditionally, overwriting
 *    the Idle ending a cancel had just published.
 *
 * Both tails are pinned here with a latch transport that parks
 * inside the issuance call — the deterministic interleaving.
 */
class R7GoogleStaleTailTest {

    /** Form transport whose device-code issuance parks on a latch. */
    private class LatchIssueTransport(
        private val issueResponse: HttpResponse,
    ) : HttpTransport {
        val issueEntered = CountDownLatch(1)
        val issueRelease = CountDownLatch(1)

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
                issueEntered.countDown()
                issueRelease.await(15, TimeUnit.SECONDS)
                issueResponse
            }
            url == GoogleOAuth.TOKEN_URL -> HttpResponse(200, "")
            else -> throw AssertionError("unexpected form POST: $url")
        }
    }

    private class UserinfoTransport : HttpTransport {
        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse = throw UnsupportedOperationException("GET only")

        override fun get(url: String, headers: Map<String, String>): HttpResponse =
            HttpResponse(200, """{"sub":"1","name":"T","email":"t@example.com"}""")
    }

    private fun manager(
        form: LatchIssueTransport,
        tokenStore: GoogleTokenStore,
        pendingStore: GooglePendingGrantStore,
        scope: CoroutineScope,
    ): GoogleSessionManager = GoogleSessionManager(
        store = tokenStore,
        auth = GoogleDeviceAuth(form),
        client = YoutubeiClient(UserinfoTransport()),
        scope = scope,
        pendingGrantStore = pendingStore,
        signInDiagnostics = SignInDiagnostics(),
    )

    @Test
    fun cancelDuringIssuanceLeavesNoGrantToResume() = runBlocking {
        val form = LatchIssueTransport(
            HttpResponse(
                200,
                """{"device_code":"dc-1","user_code":"WDJB-MJHT",""" +
                    """"verification_url":"https://www.google.com/device",""" +
                    """"expires_in":1800,"interval":3600}""",
            ),
        )
        val tokenStore = GoogleTokenStore(InMemorySecretStorage(), testSecretBox())
        val pendingStore = GooglePendingGrantStore(InMemorySecretStorage(), testSecretBox())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, tokenStore, pendingStore, scope)

        manager.startSignIn()
        assertTrue(
            "the issuance must be in flight",
            form.issueEntered.await(15, TimeUnit.SECONDS),
        )
        // The owner cancels while Google's answer is still out:
        // the epoch bumps, the job is cancelled, the (empty) grant
        // record is wiped, and the phase lands on Idle.
        manager.cancelSignIn()
        assertNull(pendingStore.load())
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)

        // The issuance now returns a perfectly good code.
        form.issueRelease.countDown()
        delay(1_500)

        // The cancelled attempt stored nothing and published
        // nothing: no grant in the sealed store, no code on screen.
        assertNull(
            "a cancelled issuance must not resurrect the grant it wiped",
            pendingStore.load(),
        )
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)

        // And a fresh manager (process death + relaunch) finds no
        // grant to resume — the cancellation is durable.
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val relaunched = manager(form, tokenStore, pendingStore, scope2)
        assertEquals(GoogleSignInPhase.Idle, relaunched.phase.value)
        scope.cancel()
        scope2.cancel()
    }

    @Test
    fun cancelDuringAFailedIssuanceKeepsTheIdleEnding() = runBlocking {
        val form = LatchIssueTransport(
            HttpResponse(500, """{"error":"internal"}"""),
        )
        val tokenStore = GoogleTokenStore(InMemorySecretStorage(), testSecretBox())
        val pendingStore = GooglePendingGrantStore(InMemorySecretStorage(), testSecretBox())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, tokenStore, pendingStore, scope)

        manager.startSignIn()
        assertTrue(form.issueEntered.await(15, TimeUnit.SECONDS))
        manager.cancelSignIn()
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)

        form.issueRelease.countDown()
        delay(1_500)

        assertEquals(
            "a stale issuance failure must not overwrite the cancel's ending",
            GoogleSignInPhase.Idle,
            manager.phase.value,
        )
        assertNull(pendingStore.load())
        scope.cancel()
    }
}
