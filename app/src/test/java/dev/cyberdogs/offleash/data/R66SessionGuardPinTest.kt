package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.GoogleDeviceAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Round 66 pins (adversarial lane, charter 2): the
 * session-presence guard legs at GoogleSessionManager's
 * entry points, generalized from round 65's LikeController
 * bearer finding. Three legs ran GREEN across the full
 * 679-test suite with the leg doctored away — no runtime
 * test had ever called these entry points on a signed-in
 * manager:
 *
 *  - [GoogleSessionManager.startSignIn]'s signed-in early
 *    return: without it, a signed-in manager issues a
 *    FRESH device grant and starts polling it — approve
 *    that code and the standing session is silently
 *    replaced by a new sign-in that never signed out.
 *  - [GoogleSessionManager.checkNow]'s signed-in early
 *    return: without it, a leftover grant persisted beside
 *    the standing session (written by a crashed or
 *    concurrent flow) is republished and its polling
 *    resumes behind the signed-in state.
 *  - restorePendingGrant's stored-session leg (its
 *    `return` after the wipe): without it, a leftover
 *    grant beside a stored session is wiped from the store
 *    and then STILL republished and polled from the
 *    in-memory copy.
 *
 * Each pin below constructs the guarded state through the
 * stores and calls the entry point; each doctor fails
 * exactly its own pin.
 */
class R66SessionGuardPinTest {

    /**
     * Form transport that records every POST and answers
     * from a script: a device-code issuance first, then
     * authorization_pending polls forever.
     */
    private class CountingFormTransport : HttpTransport {
        val calls = CopyOnWriteArrayList<String>()
        val entered = CountDownLatch(1)

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
            calls.add(formBody)
            entered.countDown()
            return if (formBody.contains("device/code") || calls.size == 1) {
                HttpResponse(
                    200,
                    """{"device_code":"dc-r66","user_code":"R66-CODE",""" +
                        """"verification_url":"https://www.google.com/device",""" +
                        """"expires_in":1800,"interval":5}""",
                )
            } else {
                HttpResponse(400, """{"error":"authorization_pending"}""")
            }
        }
    }

    private class UserinfoTransport : HttpTransport {
        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse = throw UnsupportedOperationException("GET only")

        override fun get(url: String, headers: Map<String, String>): HttpResponse =
            HttpResponse(200, "{}")
    }

    private fun tokenStoreWithSession(): GoogleTokenStore {
        val store = GoogleTokenStore(InMemorySecretStorage(), testSecretBox())
        store.save(
            StoredGoogleSession(
                accessToken = "ya29.standing",
                refreshToken = "rt-standing",
                accessTokenExpiresAtEpochMs = Long.MAX_VALUE,
                identityName = "Standing Listener",
            ),
        )
        return store
    }

    private fun pendingStoreWithLiveGrant(): GooglePendingGrantStore {
        val store = GooglePendingGrantStore(InMemorySecretStorage(), testSecretBox())
        val now = System.currentTimeMillis()
        store.save(
            StoredPendingGrant(
                deviceCode = "dc-leftover",
                userCode = "LEFT-OVER",
                verificationUrl = "https://www.google.com/device",
                issuedAtEpochMs = now - 60_000L,
                expiresAtEpochMs = now + 1_700_000L,
                intervalSeconds = 5L,
            ),
        )
        return store
    }

    private fun manager(
        form: CountingFormTransport,
        tokenStore: GoogleTokenStore,
        pendingStore: GooglePendingGrantStore? = null,
    ): GoogleSessionManager = GoogleSessionManager(
        store = tokenStore,
        auth = GoogleDeviceAuth(form),
        client = YoutubeiClient(UserinfoTransport()),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        pendingGrantStore = pendingStore,
        signInDiagnostics = SignInDiagnostics(),
    )

    @Test
    fun startSignInWhileSignedInIssuesNoGrantAndKeepsTheStandingSession() {
        val form = CountingFormTransport()
        val manager = manager(form, tokenStoreWithSession())
        assertTrue(manager.uiState.value.signedIn)
        manager.startSignIn()
        assertFalse(
            "startSignIn on a signed-in manager must not reach " +
                "the wire at all — the signed-in early return is " +
                "the only thing between the standing session and " +
                "a fresh grant whose approval would replace it",
            form.entered.await(2, TimeUnit.SECONDS),
        )
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        assertTrue(manager.uiState.value.signedIn)
        assertEquals(
            "Standing Listener",
            manager.uiState.value.identityName,
        )
    }

    @Test
    fun checkNowWhileSignedInDoesNotResumeALeftoverGrant() {
        val form = CountingFormTransport()
        val pendingStore = GooglePendingGrantStore(
            InMemorySecretStorage(),
            testSecretBox(),
        )
        val manager = manager(form, tokenStoreWithSession(), pendingStore)
        assertTrue(manager.uiState.value.signedIn)
        // The leftover state, written AFTER construction (a
        // grant persisted by a crashed or concurrent flow —
        // at construction it would have been wiped as stale):
        // checkNow's signed-in guard is the line that keeps
        // it from being republished and polled.
        val now = System.currentTimeMillis()
        pendingStore.save(
            StoredPendingGrant(
                deviceCode = "dc-leftover",
                userCode = "LEFT-OVER",
                verificationUrl = "https://www.google.com/device",
                issuedAtEpochMs = now - 60_000L,
                expiresAtEpochMs = now + 1_700_000L,
                intervalSeconds = 5L,
            ),
        )
        manager.checkNow()
        // publishAwaiting is synchronous inside checkNow when
        // the guard is absent, so the phase is the immediate
        // witness; the wire check settles any async doubt.
        assertEquals(
            "checkNow on a signed-in manager must not republish " +
                "a leftover grant's code over the signed-in state",
            GoogleSignInPhase.Idle,
            manager.phase.value,
        )
        assertFalse(
            "no poll may start for a leftover grant while signed in",
            form.entered.await(1, TimeUnit.SECONDS),
        )
        assertTrue(manager.uiState.value.signedIn)
    }

    @Test
    fun aLeftoverGrantBesideAStoredSessionIsWipedAtRestoreAndNeverPolled() {
        val form = CountingFormTransport()
        val pendingStore = pendingStoreWithLiveGrant()
        val manager = manager(form, tokenStoreWithSession(), pendingStore)
        assertTrue(manager.uiState.value.signedIn)
        assertNull(
            "a leftover grant beside a stored session is stale " +
                "and wiped at restore, not resumed",
            pendingStore.load(),
        )
        assertEquals(
            "the wiped grant must not be republished from its " +
                "in-memory copy either — restore's stored-session " +
                "leg returns right after the wipe",
            GoogleSignInPhase.Idle,
            manager.phase.value,
        )
        assertFalse(
            "no polling may run for the wiped grant",
            form.entered.await(1, TimeUnit.SECONDS),
        )
    }

    // -- Round 67 neighbours on the three pins above -------------------
    // Each of these doctors ran GREEN across the full 690-test
    // suite against the round-66 pin set (combined stacked run,
    // lane logs/combined-green-solo.log):
    //  - startSignIn's guard MOVED below the live-grant resume
    //    block: pin 1 constructs no grant, so the moved guard's
    //    only observable difference — a leftover grant beside
    //    the session being republished and polled — was untested.
    //  - restorePendingGrant's two legs REORDERED (expiry ending
    //    before the stored-session leg): pin 3's leftover is
    //    LIVE, so the reordered expiry branch never fires; with
    //    an EXPIRED leftover the reorder publishes the Failed
    //    ending over the signed-in state.
    //  - cancelSignIn's phase leg INVERTED: round 66 dismissed
    //    the leg for the signed-in direction (that state is
    //    unreachable by construction), but the reachable
    //    direction — a signed-out cancel must reset an awaiting
    //    phase to Idle — had no pin either.

    @Test
    fun startSignInWhileSignedInDoesNotResumeALeftoverGrantEither() {
        val form = CountingFormTransport()
        val pendingStore = GooglePendingGrantStore(
            InMemorySecretStorage(),
            testSecretBox(),
        )
        val manager = manager(form, tokenStoreWithSession(), pendingStore)
        assertTrue(manager.uiState.value.signedIn)
        // The leftover state, written AFTER construction (pin
        // 2's shape), now against startSignIn: its signed-in
        // guard must stand BEFORE the live-grant resume block,
        // or the leftover is republished and polled behind the
        // standing session.
        val now = System.currentTimeMillis()
        pendingStore.save(
            StoredPendingGrant(
                deviceCode = "dc-leftover",
                userCode = "LEFT-OVER",
                verificationUrl = "https://www.google.com/device",
                issuedAtEpochMs = now - 60_000L,
                expiresAtEpochMs = now + 1_700_000L,
                intervalSeconds = 5L,
            ),
        )
        manager.startSignIn()
        assertEquals(
            "startSignIn on a signed-in manager must not republish " +
                "a leftover grant's code over the signed-in state",
            GoogleSignInPhase.Idle,
            manager.phase.value,
        )
        assertFalse(
            "no poll may start for a leftover grant while signed in",
            form.entered.await(1, TimeUnit.SECONDS),
        )
        assertTrue(manager.uiState.value.signedIn)
        assertEquals(
            "Standing Listener",
            manager.uiState.value.identityName,
        )
    }

    @Test
    fun anExpiredLeftoverGrantBesideAStoredSessionIsWipedAtRestoreWithoutAFailureCard() {
        val form = CountingFormTransport()
        val pendingStore = GooglePendingGrantStore(
            InMemorySecretStorage(),
            testSecretBox(),
        )
        val now = System.currentTimeMillis()
        pendingStore.save(
            StoredPendingGrant(
                deviceCode = "dc-stale",
                userCode = "STALE-ONE",
                verificationUrl = "https://www.google.com/device",
                issuedAtEpochMs = now - 3_600_000L,
                expiresAtEpochMs = now - 60_000L,
                intervalSeconds = 5L,
            ),
        )
        val manager = manager(form, tokenStoreWithSession(), pendingStore)
        assertTrue(manager.uiState.value.signedIn)
        assertNull(
            "an expired leftover grant beside a stored session " +
                "is stale and wiped at restore",
            pendingStore.load(),
        )
        assertEquals(
            "the wiped-expired ending must NOT land either: " +
                "restore's stored-session leg runs before the " +
                "expiry branch, so no Failed phase is published " +
                "over the signed-in state",
            GoogleSignInPhase.Idle,
            manager.phase.value,
        )
        assertFalse(
            "no polling may run for the wiped grant",
            form.entered.await(1, TimeUnit.SECONDS),
        )
    }

    @Test
    fun cancelSignInWhileSignedOutResetsThePhaseAndWipesTheGrant() {
        val form = CountingFormTransport()
        val pendingStore = pendingStoreWithLiveGrant()
        val tokenStore = GoogleTokenStore(
            InMemorySecretStorage(),
            testSecretBox(),
        )
        val manager = manager(form, tokenStore, pendingStore)
        assertFalse(manager.uiState.value.signedIn)
        assertTrue(
            "the live grant is resumed at restore, so the phase " +
                "stands at AwaitingApproval before the cancel",
            manager.phase.value is GoogleSignInPhase.AwaitingApproval,
        )
        manager.cancelSignIn()
        assertEquals(
            "a signed-out cancel ends the attempt: the phase " +
                "returns to Idle (the reachable direction of the " +
                "leg round 66 dismissed for its unreachable " +
                "signed-in direction)",
            GoogleSignInPhase.Idle,
            manager.phase.value,
        )
        assertNull(
            "a cancelled grant is wiped, never resumed later",
            pendingStore.load(),
        )
    }

    // -- Round 68 neighbour on the round-67 cancel pin --------------
    // The pin above constructs the AwaitingApproval phase, so a
    // respell of cancelSignIn's leg that resets the phase only
    // from AwaitingApproval (`if (_phase.value is
    // GoogleSignInPhase.AwaitingApproval)`) passed it — and the
    // whole suite — while a signed-out cancel after a FAILED
    // attempt (the expired-at-restore ending below, or a denied
    // poll) left the failure card standing. The leg's condition
    // is the signed-in state, not the phase shape: a signed-out
    // cancel resets ANY non-Idle phase.

    @Test
    fun cancelSignInAfterARestoreFailureCardAlsoResetsThePhaseToIdle() {
        val form = CountingFormTransport()
        val pendingStore = GooglePendingGrantStore(
            InMemorySecretStorage(),
            testSecretBox(),
        )
        val now = System.currentTimeMillis()
        pendingStore.save(
            StoredPendingGrant(
                deviceCode = "dc-stale",
                userCode = "STALE-ONE",
                verificationUrl = "https://www.google.com/device",
                issuedAtEpochMs = now - 3_600_000L,
                expiresAtEpochMs = now - 60_000L,
                intervalSeconds = 5L,
            ),
        )
        val tokenStore = GoogleTokenStore(
            InMemorySecretStorage(),
            testSecretBox(),
        )
        val manager = manager(form, tokenStore, pendingStore)
        assertFalse(manager.uiState.value.signedIn)
        assertTrue(
            "the expired leftover lands on the Failed ending at " +
                "restore, so the phase stands at Failed before " +
                "the cancel",
            manager.phase.value is GoogleSignInPhase.Failed,
        )
        manager.cancelSignIn()
        assertEquals(
            "a signed-out cancel ends a failed attempt too: " +
                "the phase returns to Idle whatever non-Idle " +
                "phase the attempt ended on",
            GoogleSignInPhase.Idle,
            manager.phase.value,
        )
        assertNull(
            "the grant stays wiped after the cancel",
            pendingStore.load(),
        )
    }

    // -- Round 69 neighbour on the round-68 cancel pin --------------
    // The pins above assert the phase and the wipe after a
    // cancel, but nothing asserted that the poll JOB stops:
    // dropping `signInJob?.cancel()` from cancelSignIn ran
    // GREEN across every session-guard class, because every
    // publish the surviving loop can make is epoch-gated
    // (the cancel's bump answers them all). The one
    // observable the loop cannot hide is the wire itself: a
    // restore-resumed grant (its poll job is the
    // restore-in-progress state) whose job survives a cancel
    // polls its cancelled code at the next park boundary.
    // Proven both ways this round with this exact pin as a
    // probe: shipped passes, the job-cancel doctor fails it.

    // -- Round 70 neighbour on the round-69 wire pin -------------
    // The pin below constructs ONE interleaving: cancel during
    // the resumed poll's first park, before any wire call. The
    // neighbouring interleaving — cancel AFTER the first poll
    // has already reached the wire and the loop has re-parked —
    // was constructed by no test: the one older test that
    // cancels after polls (GooglePendingGrantTest's re-entry
    // pin) asserts the wipe only and cancels the whole scope
    // immediately after, so a surviving loop's next poll was
    // unobservable there. Proven both ways this round with
    // this exact pin as a probe: shipped passes; the round-69
    // job-cancel doctor fails it (the loop polls again at the
    // next 1-second park boundary).

    /** Poll-counting transport for the round-70 pin: polls are
     * the form posts carrying a device_code; all stay pending. */
    private class PollCountingTransport : HttpTransport {
        val polls = CopyOnWriteArrayList<String>()
        val firstPoll = CountDownLatch(1)

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
            return if (formBody.contains("device_code=")) {
                polls.add(formBody)
                firstPoll.countDown()
                HttpResponse(400, """{"error":"authorization_pending"}""")
            } else {
                HttpResponse(
                    200,
                    """{"device_code":"dc-r70","user_code":"R70-CODE",""" +
                        """"verification_url":"https://www.google.com/device",""" +
                        """"expires_in":1800,"interval":5}""",
                )
            }
        }
    }

    @Test
    fun cancelSignInAfterTheFirstWirePollStillStopsPolling() {
        val form = PollCountingTransport()
        val pendingStore = GooglePendingGrantStore(
            InMemorySecretStorage(),
            testSecretBox(),
        )
        val now = System.currentTimeMillis()
        pendingStore.save(
            StoredPendingGrant(
                deviceCode = "dc-resumed",
                userCode = "RESUMED-1",
                verificationUrl = "https://www.google.com/device",
                issuedAtEpochMs = now - 60_000L,
                expiresAtEpochMs = now + 1_700_000L,
                intervalSeconds = 1L,
            ),
        )
        val tokenStore = GoogleTokenStore(
            InMemorySecretStorage(),
            testSecretBox(),
        )
        val manager = GoogleSessionManager(
            store = tokenStore,
            auth = GoogleDeviceAuth(form),
            client = YoutubeiClient(UserinfoTransport()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            pendingGrantStore = pendingStore,
            signInDiagnostics = SignInDiagnostics(),
        )
        assertTrue(
            "the resumed poll must reach the wire once before " +
                "the cancel — this pin constructs the interleaving " +
                "the round-69 pin does not",
            form.firstPoll.await(10, TimeUnit.SECONDS),
        )
        val pollsAtCancel = form.polls.size
        manager.cancelSignIn()
        Thread.sleep(3_500)
        assertEquals(
            "no poll may reach the wire after a cancel that " +
                "lands after the first poll — the job cancel is " +
                "the only stop in this interleaving too",
            pollsAtCancel,
            form.polls.size,
        )
    }

    // -- Round 71 neighbour on the round-70 wire pin -------------
    // The round-69 pin cancels during the resumed poll's first
    // park (before any wire call) and the round-70 pin cancels
    // after the first poll returned and the loop re-parked. The
    // third interleaving — cancel while a poll is IN FLIGHT,
    // inside the wire call itself — was constructed by neither:
    // the loop's next move depends on the job cancel landing on a
    // coroutine suspended in a blocking transport call. Proven
    // both ways with this exact pin as a probe: shipped freezes
    // the poll count; the round-69 job-cancel doctor polls on
    // (four wire calls over the same window).

    /** Poll transport that blocks INSIDE the poll until released. */
    private class InFlightPollTransport : HttpTransport {
        val polls = CopyOnWriteArrayList<String>()
        val pollEntered = CountDownLatch(1)
        val pollRelease = CountDownLatch(1)

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
            return if (formBody.contains("device_code=")) {
                polls.add(formBody)
                pollEntered.countDown()
                pollRelease.await(10, TimeUnit.SECONDS)
                HttpResponse(400, """{"error":"authorization_pending"}""")
            } else {
                HttpResponse(
                    200,
                    """{"device_code":"dc-r71","user_code":"R71-CODE",""" +
                        """"verification_url":"https://www.google.com/device",""" +
                        """"expires_in":1800,"interval":5}""",
                )
            }
        }
    }

    @Test
    fun cancelSignInDuringAnInFlightWirePollStillStopsPolling() {
        val form = InFlightPollTransport()
        val pendingStore = GooglePendingGrantStore(
            InMemorySecretStorage(),
            testSecretBox(),
        )
        val now = System.currentTimeMillis()
        pendingStore.save(
            StoredPendingGrant(
                deviceCode = "dc-resumed",
                userCode = "RESUMED-1",
                verificationUrl = "https://www.google.com/device",
                issuedAtEpochMs = now - 60_000L,
                expiresAtEpochMs = now + 1_700_000L,
                intervalSeconds = 1L,
            ),
        )
        val tokenStore = GoogleTokenStore(
            InMemorySecretStorage(),
            testSecretBox(),
        )
        val manager = GoogleSessionManager(
            store = tokenStore,
            auth = GoogleDeviceAuth(form),
            client = YoutubeiClient(UserinfoTransport()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            pendingGrantStore = pendingStore,
            signInDiagnostics = SignInDiagnostics(),
        )
        assertTrue(
            "the poll must be IN FLIGHT (inside the wire call) " +
                "at the cancel — this pin constructs the " +
                "interleaving the round-69 and round-70 pins do not",
            form.pollEntered.await(10, TimeUnit.SECONDS),
        )
        manager.cancelSignIn()
        form.pollRelease.countDown()
        Thread.sleep(3_500)
        assertEquals(
            "no poll may reach the wire after a cancel that " +
                "lands while a poll is in flight",
            1,
            form.polls.size,
        )
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        assertNull(
            "a cancelled grant is wiped, never resumed later",
            pendingStore.load(),
        )
    }

    // -- Round 72 neighbours on the wire pins -------------------
    // Two more interleavings the three shipped wire pins did not
    // construct: a cancel landing in the SECOND poll's publish /
    // re-park gap (round 70's pin cancels after the FIRST poll),
    // and a sign-out — not a cancel — landing while a poll is in
    // flight inside the wire call. Both pass on the shipped code
    // and are pinned so the legs they exercise (the poll loop's
    // job cancel at the second park; signOut's own job cancel)
    // cannot silently drop.

    /** Poll transport that signals when the SECOND poll lands. */
    private class SecondPollTransport : HttpTransport {
        val polls = CopyOnWriteArrayList<String>()
        val secondPoll = CountDownLatch(1)

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
            return if (formBody.contains("device_code=")) {
                polls.add(formBody)
                if (polls.size >= 2) secondPoll.countDown()
                HttpResponse(400, """{"error":"authorization_pending"}""")
            } else {
                HttpResponse(
                    200,
                    """{"device_code":"dc-r72","user_code":"R72-CODE",""" +
                        """"verification_url":"https://www.google.com/device",""" +
                        """"expires_in":1800,"interval":5}""",
                )
            }
        }
    }

    @Test
    fun cancelSignInInTheSecondPollPublishGapStillStopsPolling() {
        val form = SecondPollTransport()
        val pendingStore = GooglePendingGrantStore(
            InMemorySecretStorage(),
            testSecretBox(),
        )
        val now = System.currentTimeMillis()
        pendingStore.save(
            StoredPendingGrant(
                deviceCode = "dc-resumed",
                userCode = "RESUMED-1",
                verificationUrl = "https://www.google.com/device",
                issuedAtEpochMs = now - 60_000L,
                expiresAtEpochMs = now + 1_700_000L,
                intervalSeconds = 1L,
            ),
        )
        val tokenStore = GoogleTokenStore(
            InMemorySecretStorage(),
            testSecretBox(),
        )
        val manager = GoogleSessionManager(
            store = tokenStore,
            auth = GoogleDeviceAuth(form),
            client = YoutubeiClient(UserinfoTransport()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            pendingGrantStore = pendingStore,
            signInDiagnostics = SignInDiagnostics(),
        )
        assertTrue(
            "the resumed poll must reach the wire TWICE before " +
                "the cancel — this pin constructs the gap after " +
                "the second poll, which the round-70 pin (first " +
                "poll) and the round-71 pin (in-flight) do not",
            form.secondPoll.await(10, TimeUnit.SECONDS),
        )
        // The second poll has returned; the loop is in its
        // publish / re-park gap when the cancel lands.
        Thread.sleep(300)
        manager.cancelSignIn()
        Thread.sleep(3_500)
        assertEquals(
            "no poll may reach the wire after a cancel that " +
                "lands in the second poll's publish gap",
            2,
            form.polls.size,
        )
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        assertNull(
            "a cancelled grant is wiped, never resumed later",
            pendingStore.load(),
        )
    }

    @Test
    fun signOutDuringAnInFlightWirePollStillStopsPolling() {
        val form = InFlightPollTransport()
        val pendingStore = GooglePendingGrantStore(
            InMemorySecretStorage(),
            testSecretBox(),
        )
        val now = System.currentTimeMillis()
        pendingStore.save(
            StoredPendingGrant(
                deviceCode = "dc-resumed",
                userCode = "RESUMED-1",
                verificationUrl = "https://www.google.com/device",
                issuedAtEpochMs = now - 60_000L,
                expiresAtEpochMs = now + 1_700_000L,
                intervalSeconds = 1L,
            ),
        )
        val tokenStore = GoogleTokenStore(
            InMemorySecretStorage(),
            testSecretBox(),
        )
        val manager = GoogleSessionManager(
            store = tokenStore,
            auth = GoogleDeviceAuth(form),
            client = YoutubeiClient(UserinfoTransport()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            pendingGrantStore = pendingStore,
            signInDiagnostics = SignInDiagnostics(),
        )
        assertTrue(
            "the poll must be IN FLIGHT (inside the wire call) " +
                "at the sign-out — the sign-out's own job cancel " +
                "is the leg on trial here, not cancelSignIn's",
            form.pollEntered.await(10, TimeUnit.SECONDS),
        )
        val signOutDone = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            runBlocking { manager.signOut() }
            signOutDone.countDown()
        }
        assertTrue(signOutDone.await(10, TimeUnit.SECONDS))
        form.pollRelease.countDown()
        Thread.sleep(3_500)
        assertEquals(
            "no poll may reach the wire after a sign-out that " +
                "lands while a poll is in flight",
            1,
            form.polls.size,
        )
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        assertNull(
            "a signed-out grant is wiped, never resumed later",
            pendingStore.load(),
        )
        assertFalse(manager.uiState.value.signedIn)
    }

    @Test
    fun cancelSignInStopsARestoreResumedPollBeforeItReachesTheWire() {
        val form = CountingFormTransport()
        val pendingStore = GooglePendingGrantStore(
            InMemorySecretStorage(),
            testSecretBox(),
        )
        val now = System.currentTimeMillis()
        pendingStore.save(
            StoredPendingGrant(
                deviceCode = "dc-resumed",
                userCode = "RESUMED-1",
                verificationUrl = "https://www.google.com/device",
                issuedAtEpochMs = now - 60_000L,
                expiresAtEpochMs = now + 1_700_000L,
                intervalSeconds = 1L,
            ),
        )
        val tokenStore = GoogleTokenStore(
            InMemorySecretStorage(),
            testSecretBox(),
        )
        val manager = manager(form, tokenStore, pendingStore)
        assertTrue(
            "the live grant is resumed at restore, so its poll " +
                "job is running before the cancel",
            manager.phase.value is GoogleSignInPhase.AwaitingApproval,
        )
        // Let the loop start — capture the grant and enter its
        // 1-second park — before cancelling: cancelling before
        // its first instruction is answered by the loop's own
        // pendingGrant null check in both shapes and proves
        // nothing about the cancel leg.
        Thread.sleep(300)
        manager.cancelSignIn()
        assertFalse(
            "no poll may reach the wire after the cancel — the " +
                "poll job's cancellation is the only thing that " +
                "stops it (every publish it could make is " +
                "epoch-gated, so the wire call is the observable)",
            form.entered.await(4, TimeUnit.SECONDS),
        )
    }
}
