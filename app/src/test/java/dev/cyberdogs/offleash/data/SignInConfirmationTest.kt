package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.data.FakeAccountTransport.Behavior
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
            (result as SignInAttempt.Failed).reason.contains("didn't include a usable"),
        )
        // One re-read proved the capture unchanged; waiting cannot
        // change cookies that are not changing, so the window stops.
        assertEquals(2, reads)
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
}
