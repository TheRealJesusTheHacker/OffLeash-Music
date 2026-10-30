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
 * Round 8 (attack the round-7 refreshIdentity publish discipline):
 * the outcomes are epoch-checked inside the lock, but the epoch is
 * captured at refresh ENTRY — and [GoogleSessionManager.signOut]
 * is not atomic between its epoch bump and its locked wipe: the
 * pending-grant wipe (sealed-store IO in production) sits between
 * them. A refresh that STARTS inside that window captures the
 * already-bumped epoch over the not-yet-wiped session, so its
 * outcome legitimately passes the epoch check — and the Rejected
 * branch, alone among the outcomes, never re-checked that a
 * session still stood: landing after sign-out's own publication
 * (the interleaving driven here, via a pending-grant storage
 * whose clear() parks), it wiped the store again and published
 * NEEDS_SIGN_IN + the "revoked" reason over the deliberate
 * signed-out ending.
 */
class R8GoogleSignOutWindowTest {

    /** Pending-grant storage whose clear() parks until released. */
    private class LatchClearStorage : SecretStorage {
        val clearEntered = CountDownLatch(1)
        val clearRelease = CountDownLatch(1)
        private var bytes: ByteArray? = null

        override fun read(): ByteArray? = bytes?.copyOf()

        override fun write(bytes: ByteArray) {
            this.bytes = bytes.copyOf()
        }

        override fun clear() {
            clearEntered.countDown()
            clearRelease.await(15, TimeUnit.SECONDS)
            bytes = null
        }
    }

    /** Userinfo transport that parks inside the GET until released, then refuses. */
    private class LatchRejectTransport : HttpTransport {
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
            return HttpResponse(401, "{}")
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
    fun refreshStartedInsideTheSignOutWindowCannotPublishTheRevokedEnding() = runBlocking {
        val userinfo = LatchRejectTransport()
        val pendingStorage = LatchClearStorage()
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
            pendingGrantStore = GooglePendingGrantStore(pendingStorage, testSecretBox()),
            signInDiagnostics = SignInDiagnostics(),
        )
        assertTrue(manager.uiState.value.signedIn)

        // Sign-out begins: the epoch bumps, then the flow parks in
        // the pending-grant wipe — before the session wipe.
        val signOut = async(Dispatchers.Default) { manager.signOut() }
        assertTrue(
            "sign-out must reach its pending-grant wipe",
            pendingStorage.clearEntered.await(15, TimeUnit.SECONDS),
        )

        // A refresh starts inside the window: the session still
        // stands, so it captures the (already bumped) epoch and
        // fetches with the standing bearer, parking in the lookup.
        val refresh = async(Dispatchers.Default) { manager.refreshIdentity() }
        assertTrue(
            "the refresh must reach its identity fetch",
            userinfo.entered.await(15, TimeUnit.SECONDS),
        )

        // Sign-out completes: session wiped, revocation answered,
        // the signed-out ending published.
        pendingStorage.clearRelease.countDown()
        signOut.await()
        assertFalse(manager.uiState.value.signedIn)
        assertEquals(SessionHealth.UNKNOWN, manager.uiState.value.health)
        assertNull(manager.uiState.value.lastEndReason)

        // The refresh's rejection finally arrives. It captured its
        // epoch after the bump, so the epoch alone cannot refuse
        // it — but the session it was fetched for is GONE, and a
        // rejection for a session that no longer stands must not
        // publish the revoked ending over the sign-out.
        userinfo.release.countDown()
        refresh.await()

        assertFalse(manager.uiState.value.signedIn)
        assertEquals(
            "a refresh started inside the sign-out window must not " +
                "resurrect the revoked ending over a completed sign-out",
            SessionHealth.UNKNOWN,
            manager.uiState.value.health,
        )
        assertNull(manager.uiState.value.lastEndReason)
    }
}
