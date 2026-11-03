package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleNativeAuth
import dev.cyberdogs.offleash.core.auth.GoogleOAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
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
 * stale outcomes, and the device-era round 7 pinned the same
 * discipline on the device flow's issuance tails. The native flow
 * (0.8.8) has exactly one network tail of its own — the code
 * exchange inside [GoogleSessionManager.completeSignIn] — and it
 * carries the same two hazards:
 *
 * 1. A cancel landing while the exchange is in flight bumps the
 *    epoch and wipes the persisted attempt; the returning grant
 *    must NOT then adopt over the cancellation (the cancelled
 *    sign-in would resurrect, sealed, after the owner said no).
 *
 * 2. A FAILED exchange returning after a cancel must not publish
 *    its Failed phase over the Idle ending the cancel published.
 *
 * Both tails are pinned here with a latch transport that parks
 * inside the exchange call — the deterministic interleaving.
 */
class R7GoogleStaleTailTest {

    /** Form transport whose token exchange parks on a latch. */
    private class LatchExchangeTransport(
        private val exchangeResponse: HttpResponse,
    ) : HttpTransport {
        val exchangeEntered = CountDownLatch(1)
        val exchangeRelease = CountDownLatch(1)

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
            url == GoogleOAuth.TOKEN_URL -> {
                exchangeEntered.countDown()
                exchangeRelease.await(15, TimeUnit.SECONDS)
                exchangeResponse
            }
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
        form: LatchExchangeTransport,
        tokenStore: GoogleTokenStore,
        attemptStore: GooglePendingNativeAuthStore,
        scope: CoroutineScope,
    ): GoogleSessionManager = GoogleSessionManager(
        store = tokenStore,
        auth = GoogleNativeAuth(form, NativeTestSupport.ANDROID_ID),
        client = YoutubeiClient(UserinfoTransport()),
        scope = scope,
        redirectUri = NativeTestSupport.REDIRECT,
        pendingAttemptStore = attemptStore,
        signInDiagnostics = SignInDiagnostics(),
    )

    @Test
    fun cancelDuringTheExchangeLeavesNothingAdopted() = runBlocking {
        val form = LatchExchangeTransport(
            HttpResponse(
                200,
                """{"access_token":"ya29.late","expires_in":3599,""" +
                    """"refresh_token":"rt-late","token_type":"Bearer"}""",
            ),
        )
        val tokenStore = GoogleTokenStore(InMemorySecretStorage(), testSecretBox())
        val attemptStore = NativeTestSupport.attemptStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, tokenStore, attemptStore, scope)

        manager.startSignIn()
        val launch = manager.authorizationLaunch.value!!
        val returning = async(Dispatchers.Default) {
            manager.completeSignIn(
                NativeTestSupport.codeRedirect(
                    NativeTestSupport.stateFrom(launch.url)
                )
            )
        }
        assertTrue(
            "the exchange must be in flight",
            form.exchangeEntered.await(15, TimeUnit.SECONDS),
        )
        // The owner cancels while the exchange is still out: the
        // epoch bumps, the persisted attempt is wiped, and the
        // phase lands on Idle.
        manager.cancelSignIn()
        assertNull(attemptStore.load())
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)

        // The exchange now returns a perfectly good grant.
        form.exchangeRelease.countDown()
        returning.await()
        delay(500)

        // The cancelled completion adopted nothing and published
        // nothing: no session sealed, no sign-in on screen.
        assertNull(
            "a cancelled exchange must not adopt the grant it returned",
            tokenStore.load(),
        )
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        scope.cancel()
    }

    @Test
    fun cancelDuringAFailedExchangeKeepsTheIdleEnding() = runBlocking {
        val form = LatchExchangeTransport(
            HttpResponse(500, """{"error":"internal"}"""),
        )
        val tokenStore = GoogleTokenStore(InMemorySecretStorage(), testSecretBox())
        val attemptStore = NativeTestSupport.attemptStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, tokenStore, attemptStore, scope)

        manager.startSignIn()
        val launch = manager.authorizationLaunch.value!!
        val returning = async(Dispatchers.Default) {
            manager.completeSignIn(
                NativeTestSupport.codeRedirect(
                    NativeTestSupport.stateFrom(launch.url)
                )
            )
        }
        assertTrue(form.exchangeEntered.await(15, TimeUnit.SECONDS))
        manager.cancelSignIn()
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)

        form.exchangeRelease.countDown()
        returning.await()
        delay(500)

        assertEquals(
            "a stale exchange failure must not overwrite the cancel's ending",
            GoogleSignInPhase.Idle,
            manager.phase.value,
        )
        assertNull(attemptStore.load())
        scope.cancel()
    }
}
