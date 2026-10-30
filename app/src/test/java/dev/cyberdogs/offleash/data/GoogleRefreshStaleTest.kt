package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleDeviceAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Round 6 (guarded-mutation census): a stale identity refresh must
 * not act on the session state that replaced it.
 *
 * [GoogleSessionManager.refreshIdentity] fetches the identity over
 * a blocking network call outside any lock, and — alone among the
 * manager's flows — never captured the flow epoch that sign-out
 * bumps precisely to stale in-flight work. A REJECTED answer for
 * the old token, returning after the owner had signed out, wiped
 * the (already wiped) store again and published NEEDS_SIGN_IN with
 * the "revoked" reason over the deliberate signed-out state; had a
 * new sign-in landed in between, the same branch would have wiped
 * THAT session. The fix captures the epoch at entry and lets no
 * outcome (Found / Rejected / Failed) apply once it has moved.
 */
class GoogleRefreshStaleTest {

    /** Userinfo transport that parks inside the GET until released. */
    private class LatchUserinfoTransport(
        private val status: Int,
    ) : HttpTransport {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse = throw UnsupportedOperationException("GET only")

        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            entered.countDown()
            release.await(15, TimeUnit.SECONDS)
            return HttpResponse(status, "{}")
        }
    }

    /** Form transport whose revocation always succeeds at once. */
    private class RevokeTransport : HttpTransport {
        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse = throw UnsupportedOperationException("form POSTs only")

        override fun postForm(
            url: String,
            headers: Map<String, String>,
            formBody: String,
        ): HttpResponse = HttpResponse(200, "")
    }

    @Test
    fun rejectedRefreshReturningAfterSignOutDoesNotOverwriteTheSignedOutState() = runBlocking {
        val userinfo = LatchUserinfoTransport(status = 401)
        val store = GoogleTokenStore(InMemorySecretStorage(), testSecretBox())
        store.save(
            StoredGoogleSession(
                accessToken = "ya29.old",
                refreshToken = "rt-old",
                accessTokenExpiresAtEpochMs = Long.MAX_VALUE,
                identityName = "Old Listener",
            ),
        )
        val manager = GoogleSessionManager(
            store = store,
            auth = GoogleDeviceAuth(RevokeTransport()),
            client = YoutubeiClient(userinfo),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            signInDiagnostics = SignInDiagnostics(),
        )
        assertTrue(manager.uiState.value.signedIn)

        val refresh = async(Dispatchers.Default) { manager.refreshIdentity() }
        assertTrue(
            "the refresh must reach its identity fetch",
            userinfo.entered.await(15, TimeUnit.SECONDS),
        )
        // The owner signs out while the fetch is parked: the epoch
        // bumps, the store wipes, the state lands signed-out/UNKNOWN.
        manager.signOut()
        assertFalse(manager.uiState.value.signedIn)
        assertEquals(SessionHealth.UNKNOWN, manager.uiState.value.health)

        // The old token's rejection finally arrives.
        userinfo.release.countDown()
        refresh.await()

        assertTrue("sign-out's wipe stands", store.isWipedClean())
        assertFalse(manager.uiState.value.signedIn)
        assertEquals(
            "a stale rejection must not resurrect a revoked ending over sign-out",
            SessionHealth.UNKNOWN,
            manager.uiState.value.health,
        )
        assertNull(manager.uiState.value.lastEndReason)
    }
}
