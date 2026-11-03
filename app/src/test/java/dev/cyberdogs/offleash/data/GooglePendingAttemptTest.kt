package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleNativeAuth
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
 * The sign-in survival suite, native form (0.8.8): the pending
 * sign-in attempt is durable app-level state, not screen state.
 * The field report it pins shut (2026-10-27, device era): "Right
 * as soon as I click out of the app the sign in process restarts"
 * — leaving for the browser (and the process death that can
 * follow) must never orphan an approval the owner is giving.
 *
 * Covered: (a) re-entering sign-in never mints a second attempt —
 * the same attempt is rendered throughout; (b) a fresh manager
 * over the same stores (process death + relaunch) resumes the
 * PERSISTED attempt and collects its return; (c) an attempt
 * sealed through the box's key recovery is resumed the same way;
 * (d) a persisted expired attempt is cleared quietly and its
 * return is never collected; (e) a return for a cancelled attempt
 * is refused — nothing is exchanged, nothing is stored.
 */
class GooglePendingAttemptTest {

    /**
     * Form-POST transport: code exchanges are counted, record the
     * code they carried, and answer with a grant. Everything else
     * is refused.
     */
    private class RoutingFormTransport : HttpTransport {
        val exchangeRequests = AtomicInteger(0)

        @Volatile
        var lastExchangedCode: String? = null
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
        ): HttpResponse = when {
            url == GoogleOAuth.TOKEN_URL &&
                formBody.contains("grant_type=authorization_code") -> {
                exchangeRequests.incrementAndGet()
                lastExchangedCode = formBody
                    .substringAfter("code=")
                    .substringBefore("&")
                HttpResponse(
                    200,
                    """{"access_token":"ya29.granted","expires_in":3599,""" +
                        """"refresh_token":"rt-1","token_type":"Bearer"}""",
                )
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
        attemptStore: GooglePendingNativeAuthStore,
        scope: CoroutineScope,
        nowMs: () -> Long = { System.currentTimeMillis() },
    ): GoogleSessionManager = GoogleSessionManager(
        store = tokenStore,
        auth = GoogleNativeAuth(form, NativeTestSupport.ANDROID_ID),
        client = YoutubeiClient(UserinfoTransport()),
        scope = scope,
        redirectUri = NativeTestSupport.REDIRECT,
        pendingAttemptStore = attemptStore,
        signInDiagnostics = SignInDiagnostics(),
        nowMs = nowMs,
    )

    private fun testTokenStore(): GoogleTokenStore =
        GoogleTokenStore(InMemorySecretStorage(), testSecretBox())

    private fun testAttemptStore(): GooglePendingNativeAuthStore =
        GooglePendingNativeAuthStore(InMemorySecretStorage(), testSecretBox())

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
    fun reenteringSignInNeverMintsASecondAttempt() = runBlocking {
        val form = RoutingFormTransport()
        val tokenStore = testTokenStore()
        val attemptStore = testAttemptStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, tokenStore, attemptStore, scope)

        manager.startSignIn()
        val first = manager.authorizationLaunch.value!!
        // The attempt was persisted the moment it was published.
        assertEquals(
            NativeTestSupport.stateFrom(first.url),
            attemptStore.load()!!.state,
        )

        // The screen is destroyed and recreated. It only ever
        // reads state — but pin the invariant at the layer the
        // bug lived in: even an explicit fresh start must resume
        // THIS attempt, never mint another.
        manager.startSignIn()
        delay(300)
        val again = manager.authorizationLaunch.value!!
        assertEquals(first.attemptId, again.attemptId)
        assertEquals(first.url, again.url)
        assertEquals(0, form.exchangeRequests.get())

        manager.cancelSignIn()
        // Cancel abandons the attempt everywhere — it can never resume.
        assertNull(attemptStore.load())
        scope.cancel()
    }

    @Test
    fun aFreshManagerResumesThePersistedAttemptAndCollectsItsReturn() =
        runBlocking {
            val form = RoutingFormTransport()
            val tokenStore = testTokenStore()
            val attemptStore = testAttemptStore()

            // First "process": an attempt is started and left
            // unanswered in the browser.
            val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val first = manager(form, tokenStore, attemptStore, scope1)
            first.startSignIn()
            val firstLaunch = first.authorizationLaunch.value!!
            val firstState = NativeTestSupport.stateFrom(firstLaunch.url)

            // Process death: the manager's whole scope dies with it.
            scope1.cancel()
            delay(250)

            // Relaunch: a brand-new manager over the same stores.
            // It republishes the SAME attempt from the sealed
            // record alone — no second minting — and the return
            // the owner brings back is collected against it.
            val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val second = manager(form, tokenStore, attemptStore, scope2)
            assertTrue(
                second.phase.value is GoogleSignInPhase.AwaitingReturn
            )
            val resumedLaunch = second.authorizationLaunch.value!!
            assertEquals(
                firstState,
                NativeTestSupport.stateFrom(resumedLaunch.url),
            )

            second.completeSignIn(
                NativeTestSupport.codeRedirect(firstState, code = "the-owners-code")
            )
            awaitTrue { second.uiState.value.signedIn }
            assertEquals("the-owners-code", form.lastExchangedCode)
            assertEquals("ya29.granted", tokenStore.load()!!.accessToken)
            // The spent attempt is gone — nothing to resume.
            assertNull(attemptStore.load())
            scope2.cancel()
        }

    @Test
    fun anAttemptSealedThroughKeyRecoveryIsResumedByAFreshManager() =
        runBlocking {
            // The owner's 0.8.0 field failure, pinned (0.8.1
            // hotfix): on his phone the seal threw
            // InvalidAlgorithmParameterException from the Keystore
            // path and durability silently did not exist. With the
            // box's scoped recovery, the SAME save path regenerates
            // the key once, the seal persists, and a fresh manager
            // resumes the same attempt.
            val form = RoutingFormTransport()
            val source = RecoveringKeySource()
            val box = AesGcmSecretBox(source::key, source::regenerate)
            val attemptStore =
                GooglePendingNativeAuthStore(InMemorySecretStorage(), box)
            val tokenStore = testTokenStore()

            val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val first = manager(form, tokenStore, attemptStore, scope1)
            first.startSignIn()
            val firstLaunch = first.authorizationLaunch.value!!
            val firstState = NativeTestSupport.stateFrom(firstLaunch.url)

            // Exactly one regeneration healed the seal, and the
            // attempt IS persisted — readable back through the box.
            assertEquals(1, source.regenerations)
            assertEquals(firstState, attemptStore.load()!!.state)

            scope1.cancel()
            delay(250)

            val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val second = manager(form, tokenStore, attemptStore, scope2)
            assertTrue(
                second.phase.value is GoogleSignInPhase.AwaitingReturn
            )
            assertEquals(
                firstState,
                NativeTestSupport.stateFrom(
                    second.authorizationLaunch.value!!.url
                ),
            )
            // Still exactly one: resuming never needed a second.
            assertEquals(1, source.regenerations)
            scope2.cancel()
        }

    @Test
    fun anExpiredPersistedAttemptIsClearedQuietlyAndNeverCollected() =
        runBlocking {
            val form = RoutingFormTransport()
            val attemptStore = testAttemptStore()
            val now = 1_700_000_000_000L
            attemptStore.save(
                StoredPendingNativeAuth(
                    state = "dead-state",
                    codeVerifier = "dead-verifier",
                    clientId = NativeTestSupport.ANDROID_ID,
                    redirectUri = NativeTestSupport.REDIRECT,
                    issuedAtEpochMs = now - 1_800_000L,
                    expiresAtEpochMs = now - 1_000L,
                )
            )

            val manager = manager(
                form,
                testTokenStore(),
                attemptStore,
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                nowMs = { now },
            )

            // Cleared at construction, quietly: no failure ending
            // for an attempt the owner may never have opened.
            assertNull(attemptStore.load())
            assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
            assertNull(manager.authorizationLaunch.value)
            assertFalse(manager.uiState.value.signedIn)

            // And a late return for it collects nothing.
            manager.completeSignIn(
                NativeTestSupport.codeRedirect("dead-state")
            )
            delay(600)
            assertEquals(0, form.exchangeRequests.get())
            assertFalse(manager.uiState.value.signedIn)
        }

    @Test
    fun aReturnForACancelledAttemptIsRefused() = runBlocking {
        val form = RoutingFormTransport()
        val tokenStore = testTokenStore()
        val attemptStore = testAttemptStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = manager(form, tokenStore, attemptStore, scope)

        manager.startSignIn()
        val launch = manager.authorizationLaunch.value!!
        val state = NativeTestSupport.stateFrom(launch.url)
        manager.cancelSignIn()

        // The browser returns anyway (the owner had it open):
        // there is no standing attempt to collect it against.
        manager.completeSignIn(NativeTestSupport.codeRedirect(state))
        delay(600)
        assertEquals(0, form.exchangeRequests.get())
        assertNull(tokenStore.load())
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        scope.cancel()
    }

    private companion object {
        const val USERINFO_BODY =
            """{"sub":"123","name":"Test Listener",""" +
                """"email":"listener@example.com",""" +
                """"picture":"https://example.com/avatar.png"}"""
    }
}
