package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleDeviceAuth
import dev.cyberdogs.offleash.core.auth.GoogleOAuth
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
 * Round 61 (fresh-eyes lane): the presented account must clear
 * when sign-out wipes the local session — not when the
 * revocation network call happens to return.
 *
 * Shipped shape before this round: [GoogleSessionManager.signOut]
 * wiped the sealed store first, then awaited the blocking
 * revoke POST, and only published the signed-out
 * [SessionUiState] after that call returned. The container's
 * presented account (accountUiState) derives from this state,
 * so for the whole duration of a slow or hanging revocation
 * the Settings/About account block kept presenting the old
 * account's name and email even though the device copy was
 * already gone — the manager's own diagnostics line states the
 * honest local truth ("the device copy is wiped regardless").
 *
 * This pin parks the revoke call on a latch and requires the
 * signed-out state (with no identity fields) to be published
 * while the revoke is still in flight.
 */
class R61SignOutPresentationTest {

    /** Revoke endpoint parks until the test releases it. */
    private class BlockingRevokeTransport : HttpTransport {
        val revokeRequests = AtomicInteger(0)
        val revokeEntered = CountDownLatch(1)
        val releaseRevoke = CountDownLatch(1)

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse = throw UnsupportedOperationException("form POSTs only")

        override fun postForm(
            url: String,
            headers: Map<String, String>,
            formBody: String,
        ): HttpResponse {
            if (url != GoogleOAuth.REVOKE_URL) {
                throw AssertionError("unexpected form POST: $url")
            }
            revokeRequests.incrementAndGet()
            revokeEntered.countDown()
            releaseRevoke.await(15, TimeUnit.SECONDS)
            return HttpResponse(200, "")
        }

        override fun get(url: String, headers: Map<String, String>): HttpResponse =
            throw UnsupportedOperationException("GET only")
    }

    private suspend fun awaitTrue(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(25)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    @Test
    fun signOutPublishesSignedOutStateBeforeTheRevokeCallReturns() = runBlocking {
        val form = BlockingRevokeTransport()
        val store = GoogleTokenStore(InMemorySecretStorage(), testSecretBox())
        store.save(
            StoredGoogleSession(
                accessToken = "ya29.held",
                refreshToken = "rt-1",
                accessTokenExpiresAtEpochMs = Long.MAX_VALUE,
                identityName = "Test Listener",
                identityEmail = "listener@example.com",
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = GoogleSessionManager(
            store = store,
            auth = GoogleDeviceAuth(form),
            client = YoutubeiClient(BlockingRevokeTransport()),
            scope = scope,
            signInDiagnostics = SignInDiagnostics(),
        )
        assertTrue("the stored session must present as signed in", manager.uiState.value.signedIn)
        assertEquals("Test Listener", manager.uiState.value.identityName)

        val signOut = async(Dispatchers.Default) { manager.signOut() }
        // The revoke call being in flight proves the local wipe
        // already happened (signOut wipes before it revokes).
        awaitTrue { form.revokeEntered.count == 0L }
        assertNull("the device copy must already be wiped", store.load())

        // While the revoke is still parked, the presented account
        // must already be the signed-out state with no identity.
        awaitTrue(timeoutMs = 5_000) { !manager.uiState.value.signedIn }
        assertNull(manager.uiState.value.identityName)
        assertNull(manager.uiState.value.identityEmail)
        assertNull(manager.uiState.value.avatarUrl)

        form.releaseRevoke.countDown()
        signOut.await()
        assertEquals(1, form.revokeRequests.get())
        assertFalse(manager.uiState.value.signedIn)
        scope.cancel()
    }
}
