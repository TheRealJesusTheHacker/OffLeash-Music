package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.Fixtures
import dev.cyberdogs.offleash.core.GetTransport
import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.auth.SessionValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Session-health mapping (Phase 2). The load-bearing rule: a network
 * failure is [SessionValidation.Unreachable], NEVER NeedsSignIn — an
 * offline phone must not read as a dead session.
 *
 * Uses the derived-synthetic switcher fixtures (see
 * [AccountIdentitiesTest] KDoc); no real account data exists here.
 */
class SessionValidationTest {

    private val session = AuthSession(
        cookies = mapOf("SAPISID" to "sap", "SID" to "sid"),
    )

    @Test
    fun foundIdentitiesMeanHealthy() {
        val client = YoutubeiClient(
            GetTransport(body = Fixtures.load("derived_account_switcher.json"))
        )
        val validation = client.validateSession(session)
        if (validation !is SessionValidation.Healthy) {
            fail("expected Healthy, was $validation")
        }
        assertEquals(2, validation.identities.size)
    }

    @Test
    fun foundEmptyMeansNeedsSignIn() {
        val client = YoutubeiClient(
            GetTransport(body = Fixtures.load("derived_account_switcher_empty.json"))
        )
        assertEquals(SessionValidation.NeedsSignIn, client.validateSession(session))
    }

    @Test
    fun rejectedMeansNeedsSignIn() {
        val unauthorized = YoutubeiClient(GetTransport(status = 401, body = ""))
        assertEquals(SessionValidation.NeedsSignIn, unauthorized.validateSession(session))
        val forbidden = YoutubeiClient(GetTransport(status = 403, body = ""))
        assertEquals(SessionValidation.NeedsSignIn, forbidden.validateSession(session))
    }

    @Test
    fun transportFailureMeansUnreachableNotSignedOut() {
        val client = YoutubeiClient(
            GetTransport(failure = RuntimeException("no route to host"))
        )
        val validation = client.validateSession(session)
        if (validation !is SessionValidation.Unreachable) {
            fail("expected Unreachable, was $validation")
        }
        assertTrue(validation.detail!!.contains("no route to host"))
    }

    @Test
    fun serverErrorMeansUnreachable() {
        val client = YoutubeiClient(GetTransport(status = 500, body = ""))
        val validation = client.validateSession(session)
        assertTrue(
            "expected Unreachable, was $validation",
            validation is SessionValidation.Unreachable
        )
    }
}
