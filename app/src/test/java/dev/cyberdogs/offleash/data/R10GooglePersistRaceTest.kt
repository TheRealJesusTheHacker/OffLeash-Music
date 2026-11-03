package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleNativeAuth
import dev.cyberdogs.offleash.core.auth.GoogleOAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Round 10 (break the round-9 terminal helpers), carried to the
 * native flow (0.8.8).
 *
 * The device-era original pinned a cancel landing between the
 * flow's epoch check and its grant persist: the tail sealed a
 * grant its own cancel had already disowned, and the next start —
 * or a fresh manager after process death — resumed a cancelled
 * code. The native flow removes that seam by construction:
 * [GoogleSessionManager.startSignIn] builds, persists, and
 * publishes the attempt inside ONE locked step with no suspension
 * point, so no cancel can land between check and persist. What
 * remains pinnable — and pinned here — is the contract outcome
 * (a cancelled attempt leaves nothing to resume, across process
 * death) and the one network tail the native flow still has: an
 * exchange returning while signOut is parked inside its attempt
 * wipe must be refused by signOut's entry epoch bump alone.
 */
class R10GooglePersistRaceTest {

    /** Form transport whose token exchange parks on a latch. */
    private class LatchExchangeTransport : HttpTransport {
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
                HttpResponse(
                    200,
                    """{"access_token":"ya29.late","expires_in":3599,""" +
                        """"refresh_token":"rt-late","token_type":"Bearer"}""",
                )
            }
            else -> HttpResponse(200, "")
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

    /** SecretStorage whose clear() parks until released. */
    private class GatedStorage : SecretStorage {
        private val inner = InMemorySecretStorage()
        val clearEntered = CountDownLatch(1)
        val clearRelease = CountDownLatch(1)
        @Volatile var gateArmed = false

        override fun read(): ByteArray? = inner.read()
        override fun write(bytes: ByteArray) = inner.write(bytes)
        override fun clear() {
            if (gateArmed) {
                clearEntered.countDown()
                clearRelease.await(15, TimeUnit.SECONDS)
            }
            inner.clear()
        }
    }

    @Test
    fun cancelAfterStartLeavesNoAttemptToResume() = runBlocking {
        val tokenStore = GoogleTokenStore(InMemorySecretStorage(), testSecretBox())
        val attemptStore = NativeTestSupport.attemptStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = GoogleSessionManager(
            store = tokenStore,
            auth = GoogleNativeAuth(
                LatchExchangeTransport(), NativeTestSupport.ANDROID_ID
            ),
            client = YoutubeiClient(UserinfoTransport()),
            scope = scope,
            redirectUri = NativeTestSupport.REDIRECT,
            pendingAttemptStore = attemptStore,
            signInDiagnostics = SignInDiagnostics(),
        )

        manager.startSignIn()
        // The persist is atomic with the publish: the attempt is
        // already sealed the moment the phase shows it.
        assertNotNull(attemptStore.load())
        manager.cancelSignIn()
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        assertNull(
            "a cancelled attempt must leave nothing to resume",
            attemptStore.load(),
        )

        // Process death + relaunch: a fresh manager over the same
        // stores must find no attempt to resume.
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val relaunched = GoogleSessionManager(
            store = tokenStore,
            auth = GoogleNativeAuth(
                LatchExchangeTransport(), NativeTestSupport.ANDROID_ID
            ),
            client = YoutubeiClient(UserinfoTransport()),
            scope = scope2,
            redirectUri = NativeTestSupport.REDIRECT,
            pendingAttemptStore = attemptStore,
            signInDiagnostics = SignInDiagnostics(),
        )
        assertEquals(
            "a fresh manager must not revive the cancelled flow",
            GoogleSignInPhase.Idle,
            relaunched.phase.value,
        )
        assertNull(relaunched.authorizationLaunch.value)
        scope.cancel()
        scope2.cancel()
    }

    @Test
    fun anUncancelledStartStillPersistsItsAttempt() {
        // The atomic persist must not over-block: an ordinary
        // start still seals its attempt and publishes its launch.
        val attemptStore = NativeTestSupport.attemptStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = GoogleSessionManager(
            store = GoogleTokenStore(InMemorySecretStorage(), testSecretBox()),
            auth = GoogleNativeAuth(
                LatchExchangeTransport(), NativeTestSupport.ANDROID_ID
            ),
            client = YoutubeiClient(UserinfoTransport()),
            scope = scope,
            redirectUri = NativeTestSupport.REDIRECT,
            pendingAttemptStore = attemptStore,
            signInDiagnostics = SignInDiagnostics(),
        )
        manager.startSignIn()
        val persisted = attemptStore.load()
        assertNotNull(persisted)
        assertEquals(
            persisted!!.state,
            NativeTestSupport.stateFrom(
                manager.authorizationLaunch.value!!.url
            ),
        )
        assertTrue(manager.phase.value is GoogleSignInPhase.AwaitingReturn)
        scope.cancel()
    }

    @Test
    fun signOutParkedInItsAttemptWipeStillRefusesAnExchangeAdopt() = runBlocking {
        // The round-73 sibling, native form: signOut carries its
        // epoch bump at entry, before its locked wipe. An exchange
        // tail returning while signOut is parked inside the
        // attempt wipe is refused by that first bump alone — the
        // wipe-lock work has not happened yet, and the completion
        // job was cancelled by the same signOut, so only the epoch
        // stands between the returning grant and adoption.
        val form = LatchExchangeTransport()
        val tokenStore = GoogleTokenStore(InMemorySecretStorage(), testSecretBox())
        val gated = GatedStorage()
        val attemptStore = GooglePendingNativeAuthStore(gated, testSecretBox())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = GoogleSessionManager(
            store = tokenStore,
            auth = GoogleNativeAuth(form, NativeTestSupport.ANDROID_ID),
            client = YoutubeiClient(UserinfoTransport()),
            scope = scope,
            redirectUri = NativeTestSupport.REDIRECT,
            pendingAttemptStore = attemptStore,
            signInDiagnostics = SignInDiagnostics(),
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
        assertTrue(
            "the exchange must be in flight",
            form.exchangeEntered.await(15, TimeUnit.SECONDS),
        )
        gated.gateArmed = true
        val signOut = async(Dispatchers.Default) { manager.signOut() }
        assertTrue(
            "signOut must park inside its attempt wipe",
            gated.clearEntered.await(15, TimeUnit.SECONDS),
        )
        // The exchange returns its grant into the parked sign-out.
        form.exchangeRelease.countDown()
        returning.await()

        gated.clearRelease.countDown()
        signOut.await()

        assertNull(
            "no session may stand after a sign-out that began " +
                "before the exchange returned",
            tokenStore.load(),
        )
        assertNull(attemptStore.load())
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        scope.cancel()
    }
}
