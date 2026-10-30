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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Round 11 (breaking the round-10 persist helper): the helper's
 * preserved caveat, pinned at runtime. Round 10 moved the grant
 * persist into persistPendingGrantIfCurrent — assignment BEFORE
 * the sealed save, inside the lock — deliberately unlike adopt(),
 * which saves before assigning: a device grant's poll runs from
 * memory, so a save failure must not kill the attempt. The
 * claims under attack, and their verdicts in this test:
 *
 * - A non-CancellationException save failure is CONTAINED in the
 *   helper (it does not escape into runSignIn's catch, which
 *   would have landed the attempt on the Failed card): the
 *   phase reaches AwaitingApproval.
 * - The in-memory grant STANDS after the failed save: a second
 *   startSignIn resumes it instead of issuing a new code
 *   (issuance count stays 1).
 * - Nothing is SEALED: the pending store stays empty, and a
 *   fresh manager over the same stores starts at Idle — only
 *   the survive-a-restart guarantee is lost, exactly as the
 *   caveat (and its diagnostics line) says.
 */
class R11GooglePersistCaveatTest {

    /** Form transport that counts device-code issuances. */
    private class CountingIssueTransport(
        val issuances: AtomicInteger,
    ) : HttpTransport {
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
                issuances.incrementAndGet()
                HttpResponse(
                    200,
                    """{"device_code":"dc-1","user_code":"WDJB-MJHT",""" +
                        """"verification_url":"https://www.google.com/device",""" +
                        """"expires_in":1800,"interval":3600}""",
                )
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

    /** A pending-grant storage whose every write fails. */
    private class ThrowingWriteStorage : SecretStorage {
        override fun read(): ByteArray? = null
        override fun write(bytes: ByteArray) {
            throw RuntimeException("simulated storage failure")
        }
        override fun clear() {}
    }

    @Test
    fun grantSaveFailureKeepsTheAttemptAliveInProcessAndSealsNothing() = runBlocking {
        val issuances = AtomicInteger(0)
        val transport = CountingIssueTransport(issuances)
        val tokenStore = GoogleTokenStore(InMemorySecretStorage(), testSecretBox())
        val pendingStore = GooglePendingGrantStore(ThrowingWriteStorage(), testSecretBox())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = GoogleSessionManager(
            store = tokenStore,
            auth = GoogleDeviceAuth(transport),
            client = YoutubeiClient(UserinfoTransport()),
            scope = scope,
            pendingGrantStore = pendingStore,
            signInDiagnostics = SignInDiagnostics(),
        )

        manager.startSignIn()
        awaitTrue {
            manager.phase.value is GoogleSignInPhase.AwaitingApproval
        }
        assertNull(
            "a failed save must leave nothing sealed",
            pendingStore.load(),
        )
        assertEquals(1, issuances.get())

        // The in-memory grant stands: starting again resumes it
        // instead of burning a second code at Google.
        manager.startSignIn()
        delay(250)
        assertEquals(
            "a save failure must not lose the in-process grant: the " +
                "second start resumes it rather than re-issuing",
            1,
            issuances.get(),
        )
        assertTrue(manager.phase.value is GoogleSignInPhase.AwaitingApproval)

        // The caveat's other half: with nothing sealed, a fresh
        // manager after process death has nothing to resume.
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val relaunched = GoogleSessionManager(
            store = tokenStore,
            auth = GoogleDeviceAuth(transport),
            client = YoutubeiClient(UserinfoTransport()),
            scope = scope2,
            pendingGrantStore = pendingStore,
            signInDiagnostics = SignInDiagnostics(),
        )
        assertEquals(GoogleSignInPhase.Idle, relaunched.phase.value)
        scope.cancel()
        scope2.cancel()
    }

    private suspend fun awaitTrue(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("condition not met within ${timeoutMs}ms")
            }
            delay(25)
        }
    }
}
