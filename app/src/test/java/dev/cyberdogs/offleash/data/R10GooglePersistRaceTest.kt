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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Round 10 (break the round-9 Google terminal helpers): round 9
 * locked the poll loop's terminal applies and runSignIn's phase
 * publishes, and round 7 had moved runSignIn's epoch check ahead
 * of its grant persist — but the check and the persist were still
 * two statements. A cancel landing between them (a sign-out is
 * the same shape) had its clear wiped nothing yet, and the tail
 * then assigned and SEALED the grant anyway: the locked publish
 * correctly refused to show the cancelled code, but the grant
 * stood in memory and in the sealed store, so the next
 * startSignIn (livePendingGrant) — or a fresh manager after
 * process death (restorePendingGrant) — revived a flow the owner
 * had cancelled, breaking cancelSignIn's "a cancelled code is
 * never resumed" contract one step later.
 *
 * The deterministic interleaving rides the manager's injected
 * clock: the grant-construction `nowMs()` call in runSignIn is
 * the first clock read after issuance returns, and it sits
 * exactly between the round-7/9 epoch check and the persist.
 * The fake clock performs the cancel re-entrantly at that call,
 * then the tail runs on.
 *
 * The fix: the assignment and the sealed save happen inside
 * [GoogleSessionManager]'s locked epoch-checked step
 * (persistPendingGrantIfCurrent) — check and persist are one
 * locked step, like every other apply in the file.
 */
class R10GooglePersistRaceTest {

    /** Form transport answering issuance immediately. */
    private class ImmediateIssueTransport : HttpTransport {
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
            url == GoogleOAuth.DEVICE_CODE_URL -> HttpResponse(
                200,
                """{"device_code":"dc-1","user_code":"WDJB-MJHT",""" +
                    """"verification_url":"https://www.google.com/device",""" +
                    """"expires_in":1800,"interval":3600}""",
            )
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

    @Test
    fun cancelBetweenEpochCheckAndPersistLeavesNoGrantToResume() = runBlocking {
        val tokenStore = GoogleTokenStore(InMemorySecretStorage(), testSecretBox())
        val pendingStore = GooglePendingGrantStore(InMemorySecretStorage(), testSecretBox())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val managerRef = AtomicReference<GoogleSessionManager?>(null)
        val injected = AtomicBoolean(false)
        val cancelInjected = CountDownLatch(1)
        // The first clock read after issuance is runSignIn's
        // grant construction — the exact seam between the epoch
        // check and the persist. Cancel there, re-entrantly.
        val racingClock: () -> Long = {
            if (injected.compareAndSet(false, true)) {
                managerRef.get()?.cancelSignIn()
                cancelInjected.countDown()
            }
            System.currentTimeMillis()
        }
        val manager = GoogleSessionManager(
            store = tokenStore,
            auth = GoogleDeviceAuth(ImmediateIssueTransport()),
            client = YoutubeiClient(UserinfoTransport()),
            scope = scope,
            pendingGrantStore = pendingStore,
            signInDiagnostics = SignInDiagnostics(),
            nowMs = racingClock,
        )
        managerRef.set(manager)

        manager.startSignIn()
        assertTrue(
            "the cancel must be injected at the grant-construction clock read",
            cancelInjected.await(15, TimeUnit.SECONDS),
        )
        // Let runSignIn's non-suspending tail run past the persist
        // and the locked publish (the R7 tests' settle window).
        delay(1_500)

        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        assertNull(
            "a grant persisted after its flow was cancelled is a zombie: " +
                "the next startSignIn or a fresh manager would resume a " +
                "code the owner cancelled",
            pendingStore.load(),
        )

        // Process death + relaunch: a fresh manager over the same
        // stores must find no grant to resume.
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val relaunched = GoogleSessionManager(
            store = tokenStore,
            auth = GoogleDeviceAuth(ImmediateIssueTransport()),
            client = YoutubeiClient(UserinfoTransport()),
            scope = scope2,
            pendingGrantStore = pendingStore,
            signInDiagnostics = SignInDiagnostics(),
        )
        assertEquals(
            "a fresh manager must not revive the cancelled flow",
            GoogleSignInPhase.Idle,
            relaunched.phase.value,
        )
        scope.cancel()
        scope2.cancel()
    }

    @Test
    fun uncancelledIssuanceStillPersistsItsGrant() = runBlocking {
        // The locked persist must not over-block: an ordinary
        // sign-in still seals its grant and publishes its code.
        val tokenStore = GoogleTokenStore(InMemorySecretStorage(), testSecretBox())
        val pendingStore = GooglePendingGrantStore(InMemorySecretStorage(), testSecretBox())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = GoogleSessionManager(
            store = tokenStore,
            auth = GoogleDeviceAuth(ImmediateIssueTransport()),
            client = YoutubeiClient(UserinfoTransport()),
            scope = scope,
            pendingGrantStore = pendingStore,
            signInDiagnostics = SignInDiagnostics(),
        )
        manager.startSignIn()
        awaitTrue {
            pendingStore.load()?.deviceCode == "dc-1" &&
                manager.phase.value is GoogleSignInPhase.AwaitingApproval
        }
        scope.cancel()
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

    // -- Round 73 sibling: signOut's FIRST epoch bump -----------
    // The pin above covers cancelSignIn's bump at this seam.
    // signOut carries TWO bumps — one at entry, one inside its
    // wipe lock (round 8) — and dropping the FIRST ran GREEN
    // across the whole suite: no construction held an issuance
    // tail inside the window between them. That window is the
    // grant wipe itself (sealed-store IO in production): a
    // runSignIn tail reaching its persist while signOut is
    // parked inside the wipe is refused by the first bump
    // alone — the wipe-lock bump has not happened yet, and the
    // tail's own job was cancelled by the same signOut, so
    // only the epoch stands. Without the first bump the tail
    // seals its grant AFTER sign-out cleared, and the next
    // startSignIn resumes a code from a signed-out manager.
    // The construction gates signOut inside the wipe through
    // a SecretStorage whose clear() parks, and holds the
    // issuance tail at grant construction through the
    // injected clock — the round-10 seam.
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
    fun signOutParkedInItsGrantWipeStillRefusesAnIssuancePersist() {
        val tokenStore = GoogleTokenStore(InMemorySecretStorage(), testSecretBox())
        val gated = GatedStorage()
        val pendingStore = GooglePendingGrantStore(gated, testSecretBox())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val managerRef = AtomicReference<GoogleSessionManager?>(null)
        val injected = AtomicBoolean(false)
        val signOutParked = CountDownLatch(1)
        val signOutDone = CountDownLatch(1)
        // First clock read after issuance = grant construction,
        // the seam between runSignIn's epoch capture and its
        // persist (the round-10 seam). There, launch signOut
        // on its own thread and hold this tail until signOut
        // is parked inside its grant wipe — past its first
        // epoch bump and its job cancel, before its wipe-lock
        // bump.
        val racingClock: () -> Long = {
            if (injected.compareAndSet(false, true)) {
                val m = managerRef.get()
                if (m != null) {
                    gated.gateArmed = true
                    val t = Thread {
                        runBlocking { m.signOut() }
                        signOutDone.countDown()
                    }
                    t.start()
                    if (gated.clearEntered.await(15, TimeUnit.SECONDS)) {
                        signOutParked.countDown()
                    }
                }
            }
            System.currentTimeMillis()
        }
        val manager = GoogleSessionManager(
            store = tokenStore,
            auth = GoogleDeviceAuth(ImmediateIssueTransport()),
            client = YoutubeiClient(UserinfoTransport()),
            scope = scope,
            pendingGrantStore = pendingStore,
            signInDiagnostics = SignInDiagnostics(),
            nowMs = racingClock,
        )
        managerRef.set(manager)
        manager.startSignIn()
        assertTrue(
            "signOut must park inside its grant wipe while " +
                "the issuance tail is held at grant construction",
            signOutParked.await(15, TimeUnit.SECONDS),
        )
        // The tail has run on by now (the clock returned once
        // signOut parked): give its persist its moment, then
        // release signOut's wipe and let it finish.
        Thread.sleep(500)
        gated.clearRelease.countDown()
        assertTrue(signOutDone.await(15, TimeUnit.SECONDS))
        assertNull(
            "no grant may stand after a sign-out that began " +
                "before the issuance persisted — the first " +
                "epoch bump is the only refusal the tail can " +
                "see from inside the wipe window",
            pendingStore.load(),
        )
        assertEquals(GoogleSignInPhase.Idle, manager.phase.value)
        // And the zombie check: a fresh startSignIn must ISSUE
        // (phase stays non-Awaiting until issuance lands), not
        // resume a sealed leftover. With no leftover it issues
        // a new grant whose code then awaits — indistinguishable
        // by phase alone, so the store read above is the pin;
        // this step only proves the manager still functions.
        manager.startSignIn()
        Thread.sleep(500)
        gated.gateArmed = false
    }

}
