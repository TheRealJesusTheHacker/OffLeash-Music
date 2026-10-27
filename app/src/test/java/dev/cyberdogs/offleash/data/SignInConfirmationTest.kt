package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.SessionCookies
import dev.cyberdogs.offleash.data.FakeAccountTransport.Behavior
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sign-in confirmation window (2026-10-24 sign-in hunt).
 *
 * The bounce this pins against: a real login completed on Google's
 * page, then ONE one-shot identity check — run against a cookie
 * string captured at the first page-finish, before the session had
 * settled on YouTube's side — answered "no account" and the sign-in
 * was refused outright. Confirmation is now patient: it re-reads
 * the cookie source and re-asks across a window, adopts the first
 * confirmed identity, and only fails when the window honestly
 * exhausts — with rollback intact throughout (nothing is stored
 * before confirmation, ever).
 */
class SignInConfirmationTest {

    private val delays = listOf(0L, 0L, 0L) // four attempts, instantly

    @Test
    fun emptyAnswersConvergeOnceTheSessionIsRecognized() = runBlocking {
        val store = testSessionStore()
        val transport = FakeAccountTransport()
        transport.behaviorQueue.addAll(
            listOf(Behavior.EMPTY, Behavior.EMPTY, Behavior.IDENTITIES),
        )
        val manager = testSessionManager(store, transport, confirmationDelaysMs = delays)

        val result = manager.completeSignIn(FakeAccountTransport.RAW_COOKIE)

        assertTrue("expected Success, was $result", result is SignInAttempt.Success)
        assertEquals(3, transport.accountCallCount)
        val persisted = store.load()
        assertNotNull(persisted)
        assertEquals(FakeAccountTransport.IDENTITY_EMAIL, persisted!!.identityEmail)
    }

    @Test
    fun anEarlyRefusalConvergesToo() = runBlocking {
        val store = testSessionStore()
        val transport = FakeAccountTransport()
        transport.behaviorQueue.addAll(listOf(Behavior.REJECTED, Behavior.IDENTITIES))
        val manager = testSessionManager(store, transport, confirmationDelaysMs = delays)

        val result = manager.completeSignIn(FakeAccountTransport.RAW_COOKIE)

        assertTrue("expected Success, was $result", result is SignInAttempt.Success)
        assertEquals(2, transport.accountCallCount)
    }

    @Test
    fun exhaustedWindowFailsAndLeavesTheStoredSessionUntouched() = runBlocking {
        val store = testSessionStore()
        val seeded = seededStoredSession()
        store.save(seeded)
        val transport = FakeAccountTransport(behavior = Behavior.EMPTY)
        val manager = testSessionManager(store, transport, confirmationDelaysMs = delays)

        val result = manager.completeSignIn(FakeAccountTransport.RAW_COOKIE)

        assertTrue("expected Failed, was $result", result is SignInAttempt.Failed)
        assertTrue(
            (result as SignInAttempt.Failed).reason.contains("didn't confirm"),
        )
        assertEquals(delays.size + 1, transport.accountCallCount)
        // Rollback: the previously stored session is byte-for-byte intact.
        assertEquals(seeded, store.load())
    }

    @Test
    fun evolvingCaptureIsReReadAcrossTheWindow() = runBlocking {
        val store = testSessionStore()
        val transport = FakeAccountTransport()
        val manager = testSessionManager(store, transport, confirmationDelaysMs = delays)
        var reads = 0
        val provider: suspend () -> String = {
            reads++
            if (reads == 1) "PREF=not-a-session" else FakeAccountTransport.RAW_COOKIE
        }

        val result = manager.completeSignIn(provider)

        assertTrue("expected Success, was $result", result is SignInAttempt.Success)
        assertEquals(2, reads)
        assertEquals(1, transport.accountCallCount)
    }

    @Test
    fun unchangedImplausibleCaptureStopsAtTheSubsetStage() = runBlocking {
        val store = testSessionStore()
        val transport = FakeAccountTransport()
        val manager = testSessionManager(store, transport, confirmationDelaysMs = delays)
        var reads = 0
        val provider: suspend () -> String = {
            reads++
            "PREF=not-a-session"
        }

        val result = manager.completeSignIn(provider)

        assertTrue("expected Failed, was $result", result is SignInAttempt.Failed)
        assertTrue(
            (result as SignInAttempt.Failed).reason.contains("didn't give the app a usable"),
        )
        // One re-read proved the capture unchanged; waiting cannot
        // change cookies that are not changing, so the window stops.
        assertEquals(2, reads)
        assertEquals(0, transport.accountCallCount)
    }

    // ------------------------------------------------------------------
    // Cookie hunt (2026-10-27): 0.6.4 on the owner's phone read the
    // capture EMPTY twice inside two seconds and the unchanged-abort
    // sealed it — "capture unchanged between attempts" — while the
    // WebView showed the signed-in page. Two identical EMPTY reads
    // prove only that nothing had landed yet; the sign-in flow can
    // plant the session cookies after them. The unchanged exemption
    // now applies only to a NON-BLANK capture (the pasted-garbage
    // case above, which keeps its early stop).
    // ------------------------------------------------------------------

    @Test
    fun blankCapturesDoNotStopTheWindow() = runBlocking {
        val store = testSessionStore()
        val transport = FakeAccountTransport()
        val manager = testSessionManager(store, transport, confirmationDelaysMs = delays)
        var reads = 0
        val provider: suspend () -> String = {
            reads++
            if (reads <= 2) "" else FakeAccountTransport.RAW_COOKIE
        }

        val result = manager.completeSignIn(provider)

        assertTrue("expected Success, was $result", result is SignInAttempt.Success)
        assertEquals(3, reads)
        assertEquals(1, transport.accountCallCount)
    }

    @Test
    fun blankCaptureThroughoutExhaustsEveryAttempt() = runBlocking {
        val store = testSessionStore()
        val transport = FakeAccountTransport()
        val manager = testSessionManager(store, transport, confirmationDelaysMs = delays)
        var reads = 0
        val provider: suspend () -> String = {
            reads++
            ""
        }

        val result = manager.completeSignIn(provider)

        assertTrue("expected Failed, was $result", result is SignInAttempt.Failed)
        assertTrue(
            (result as SignInAttempt.Failed).reason.contains("didn't give the app a usable"),
        )
        // No early stop on empty reads: the full window ran.
        assertEquals(delays.size + 1, reads)
        assertEquals(0, transport.accountCallCount)
    }

    @Test
    fun unreachableThroughoutReportsUnreachableNotUnconfirmed() = runBlocking {
        val store = testSessionStore()
        val transport = FakeAccountTransport(behavior = Behavior.THROW)
        val manager = testSessionManager(store, transport, confirmationDelaysMs = delays)

        val result = manager.completeSignIn(FakeAccountTransport.RAW_COOKIE)

        assertTrue("expected Failed, was $result", result is SignInAttempt.Failed)
        assertTrue(
            (result as SignInAttempt.Failed).reason.contains("Couldn't reach"),
        )
        assertEquals(delays.size + 1, transport.accountCallCount)
    }

    @Test
    fun diagnosticsNameTheFailingStage() = runBlocking {
        val store = testSessionStore()
        val transport = FakeAccountTransport(behavior = Behavior.EMPTY)
        val diagnostics = SignInDiagnostics()
        val manager = testSessionManager(
            store,
            transport,
            confirmationDelaysMs = delays,
            signInDiagnostics = diagnostics,
        )

        manager.completeSignIn(FakeAccountTransport.RAW_COOKIE)

        val lines = diagnostics.lines()
        assertTrue(
            "expected a cookies stage line, was: $lines",
            lines.any { it.contains("cookies:") },
        )
        assertTrue(
            "expected an identity stage line naming the empty answer, was: $lines",
            lines.any { it.contains("identity:") && it.contains("listed no account") },
        )
        assertTrue(
            "expected the window-exhausted line, was: $lines",
            lines.any { it.contains("window exhausted") },
        )
    }

    // ------------------------------------------------------------------
    // Crash-hunt adoption corridor (2026-10-27): 0.6.3 crashed the
    // whole app ~1s into the owner's first real sign-in. The parse
    // was proven innocent (23 structural variants, zero escapes);
    // the corridor downstream of a confirmed identity was not —
    // adoption's save is the first Keystore seal and first session
    // write of the app's life, and nothing caught its failure, so it
    // escaped into the sign-in screen's handler-less launch. These
    // tests pin the corridor shut: a device-side save failure is a
    // polite Failed naming the store stage, with nothing stored.
    // ------------------------------------------------------------------

    @Test
    fun sealFailureDuringAdoptionFailsPolitelyAndStoresNothing() = runBlocking {
        val store = SessionStore(
            InMemorySecretStorage(),
            AesGcmSecretBox { throw RuntimeException("keystore unavailable in test") },
        )
        val transport = FakeAccountTransport()
        val diagnostics = SignInDiagnostics()
        val manager = testSessionManager(
            store, transport,
            confirmationDelaysMs = delays,
            signInDiagnostics = diagnostics,
        )

        val result = manager.completeSignIn(FakeAccountTransport.RAW_COOKIE)

        assertTrue("expected Failed, was $result", result is SignInAttempt.Failed)
        assertTrue(
            (result as SignInAttempt.Failed).reason.contains("couldn't save the session"),
        )
        // The identity WAS confirmed (the account call happened);
        // only the save failed, and nothing was stored.
        assertEquals(1, transport.accountCallCount)
        assertNull(store.load())
        assertTrue(
            "expected a store stage line, was: ${diagnostics.lines()}",
            diagnostics.lines().any { it.contains("store:") && it.contains("couldn't be saved") },
        )
    }

    @Test
    fun writeFailureDuringAdoptionFailsPolitelyAndStoresNothing() = runBlocking {
        val storage = object : SecretStorage {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) {
                throw IOException("simulated storage failure")
            }
            override fun clear() {}
        }
        val store = SessionStore(storage, testSecretBox())
        val transport = FakeAccountTransport()
        val manager = testSessionManager(store, transport, confirmationDelaysMs = delays)

        val result = manager.completeSignIn(FakeAccountTransport.RAW_COOKIE)

        assertTrue("expected Failed, was $result", result is SignInAttempt.Failed)
        assertTrue(
            (result as SignInAttempt.Failed).reason.contains("couldn't save the session"),
        )
        assertNull(store.load())
    }

    // ------------------------------------------------------------------
    // Paste fix (2026-10-27): the manual fallback runs the pasted
    // text through core's tolerant normalizer and then the SAME
    // pipeline as the WebView capture. A paste wearing its transport
    // clothes (a `Cookie:` label, one pair per line, an extra
    // non-session cookie riding along) confirms and adopts like any
    // capture; a paste that parses but lacks a session piece fails
    // at the subset stage with the missing piece NAMED in the
    // diagnostics (names only, never values).
    // ------------------------------------------------------------------

    @Test
    fun messyPasteNormalizesAndAdopts() = runBlocking {
        val store = testSessionStore()
        val transport = FakeAccountTransport()
        val manager = testSessionManager(store, transport, confirmationDelaysMs = delays)
        val paste = "Cookie: SAPISID=test-sapisid-value\n" +
            "__Secure-3PAPISID=test-3p-value; SID=test-sid-value\n" +
            "LOGIN_INFO=test-login-info; CONSENT=YES+1"
        val normalized = SessionCookies.normalizePastedCookie(paste)

        val result = manager.completeSignIn(normalized.cookieString)

        assertTrue("expected Success, was $result", result is SignInAttempt.Success)
        assertEquals(1, transport.accountCallCount)
        val persisted = store.load()
        assertNotNull(persisted)
        assertEquals(FakeAccountTransport.IDENTITY_EMAIL, persisted!!.identityEmail)
    }

    @Test
    fun pasteMissingASessionPieceNamesItInTheDiagnostics() = runBlocking {
        val store = testSessionStore()
        val transport = FakeAccountTransport()
        val diagnostics = SignInDiagnostics()
        val manager = testSessionManager(
            store,
            transport,
            confirmationDelaysMs = delays,
            signInDiagnostics = diagnostics,
        )
        // A hash source but no session id anywhere in the paste.
        val normalized = SessionCookies.normalizePastedCookie(
            "SAPISID=test-sapisid-value; PREF=f1=50000000"
        )

        val result = manager.completeSignIn(normalized.cookieString)

        assertTrue("expected Failed, was $result", result is SignInAttempt.Failed)
        assertTrue(
            (result as SignInAttempt.Failed).reason.contains("didn't give the app a usable"),
        )
        // The identity check is never reached, and the card's
        // diagnostics name the missing piece.
        assertEquals(0, transport.accountCallCount)
        assertTrue(
            "expected the missing session id named, was: ${diagnostics.lines()}",
            diagnostics.lines().any { it.contains("session id (SID or LOGIN_INFO)") },
        )
        assertNull(store.load())
    }

    @Test
    fun errorEscapingTheIdentityCheckFailsPolitely() = runBlocking {
        // The core contract says the identity lookup never throws —
        // but an Error (not an Exception) is outside every catch the
        // client makes. The manager is the last line: it must turn
        // even that into the failure card, immediately.
        val transport = object : HttpTransport {
            override fun post(
                url: String,
                headers: Map<String, String>,
                body: String,
            ): HttpResponse = HttpResponse(200, "")

            override fun get(url: String, headers: Map<String, String>): HttpResponse {
                throw AssertionError("simulated error escape")
            }
        }
        val store = testSessionStore()
        val diagnostics = SignInDiagnostics()
        val manager = SessionManager(
            store = store,
            client = YoutubeiClient(transport),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            signInDiagnostics = diagnostics,
            confirmationDelaysMs = delays,
        )

        val result = manager.completeSignIn(FakeAccountTransport.RAW_COOKIE)

        assertTrue("expected Failed, was $result", result is SignInAttempt.Failed)
        assertTrue(
            (result as SignInAttempt.Failed).reason.contains("Something went wrong"),
        )
        assertNull(store.load())
        assertTrue(
            "expected an identity stage line naming the escape, was: ${diagnostics.lines()}",
            diagnostics.lines().any {
                it.contains("identity:") && it.contains("failed unexpectedly")
            },
        )
    }
}
