package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleNativeAuth
import dev.cyberdogs.offleash.core.auth.GoogleOAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 11 (breaking the round-10 persist helper): the persist
 * caveat, pinned at runtime — carried to the native flow (0.8.8).
 *
 * The device-era original pinned the grant persist's deliberate
 * caveat: assignment before the sealed save, so a save failure
 * must not kill an attempt whose poll runs from memory. The
 * native attempt persist carries the same caveat in
 * [GoogleSessionManager.startSignIn]: the attempt is assigned and
 * published first, the sealed save is attempted inside the same
 * locked step, and a failure is contained — recorded on
 * [GoogleSessionManager.pendingAttemptDurability] and in
 * diagnostics — never thrown into the flow. The claims under
 * attack, and their verdicts in this test:
 *
 * - A save failure is CONTAINED: the phase reaches
 *   AwaitingReturn and the launch is published.
 * - The in-memory attempt STANDS after the failed save: a
 *   second startSignIn resumes the SAME attempt (same launch)
 *   instead of minting a new one, and the return still completes
 *   the sign-in in this process.
 * - Nothing is SEALED: the attempt store stays empty, and a
 *   fresh manager over the same stores starts at Idle — only
 *   the survive-a-restart guarantee is lost, exactly as the
 *   caveat (and its diagnostics line) says.
 */
class R11GooglePersistCaveatTest {

    /** Form transport answering the code exchange with a grant. */
    private class GrantTransport : HttpTransport {
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
            url == GoogleOAuth.TOKEN_URL -> HttpResponse(
                200,
                """{"access_token":"ya29.granted","expires_in":3599,""" +
                    """"refresh_token":"rt-1","token_type":"Bearer"}""",
            )
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

    /** A pending-attempt storage whose every write fails. */
    private class ThrowingWriteStorage : SecretStorage {
        override fun read(): ByteArray? = null
        override fun write(bytes: ByteArray) {
            throw RuntimeException("simulated storage failure")
        }
        override fun clear() {}
    }

    @Test
    fun attemptSaveFailureKeepsTheAttemptAliveInProcessAndSealsNothing() =
        runBlocking {
            val tokenStore =
                GoogleTokenStore(InMemorySecretStorage(), testSecretBox())
            val attemptStore = GooglePendingNativeAuthStore(
                ThrowingWriteStorage(), testSecretBox()
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manager = GoogleSessionManager(
                store = tokenStore,
                auth = GoogleNativeAuth(
                    GrantTransport(), NativeTestSupport.ANDROID_ID
                ),
                client = YoutubeiClient(UserinfoTransport()),
                scope = scope,
                redirectUri = NativeTestSupport.REDIRECT,
                pendingAttemptStore = attemptStore,
                signInDiagnostics = SignInDiagnostics(),
            )

            manager.startSignIn()
            assertTrue(
                manager.phase.value is GoogleSignInPhase.AwaitingReturn
            )
            val launch = manager.authorizationLaunch.value
            assertTrue("the launch must be published", launch != null)
            assertFalse(
                "the durability caveat must be recorded",
                manager.pendingAttemptDurability.value,
            )
            assertNull(
                "a failed save must leave nothing sealed",
                attemptStore.load(),
            )

            // The in-memory attempt stands: starting again resumes
            // the SAME attempt instead of minting a new one.
            manager.startSignIn()
            assertEquals(
                "a save failure must not lose the in-process attempt",
                launch!!.attemptId,
                manager.authorizationLaunch.value?.attemptId,
            )

            // And the return still completes the sign-in in this
            // process — the caveat costs restart-survival only.
            // The exchange completes on the manager's own scope,
            // so wait for the adoption with a bounded deadline
            // rather than reading the state in the same instant.
            manager.completeSignIn(
                NativeTestSupport.codeRedirect(
                    NativeTestSupport.stateFrom(launch.url)
                )
            )
            val adopted = withTimeoutOrNull(10_000) {
                while (!manager.uiState.value.signedIn) delay(10)
                true
            }
            assertTrue(
                "the return must complete the sign-in in this process",
                adopted == true,
            )

            // The caveat's other half: with nothing sealed, a
            // fresh manager after process death has nothing to
            // resume. (Fresh token store too: the sign-in above
            // sealed a session, which is not what is under test.)
            val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val relaunched = GoogleSessionManager(
                store = GoogleTokenStore(
                    InMemorySecretStorage(), testSecretBox()
                ),
                auth = GoogleNativeAuth(
                    GrantTransport(), NativeTestSupport.ANDROID_ID
                ),
                client = YoutubeiClient(UserinfoTransport()),
                scope = scope2,
                redirectUri = NativeTestSupport.REDIRECT,
                pendingAttemptStore = attemptStore,
                signInDiagnostics = SignInDiagnostics(),
            )
            assertEquals(GoogleSignInPhase.Idle, relaunched.phase.value)
            assertNull(relaunched.authorizationLaunch.value)
            scope.cancel()
            scope2.cancel()
        }
}
