package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.Fixtures
import dev.cyberdogs.offleash.core.GetTransport
import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.auth.IdentityLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account-switcher parsing and outcome mapping (Phase 2).
 *
 * The `derived_*.json` fixtures are DERIVED-SYNTHETIC: they were built
 * by hand to the documented switcher shape — this project holds no real
 * account capture, and no real cookie or identity appears in them.
 */
class AccountIdentitiesTest {

    private val session = AuthSession(
        cookies = mapOf("SAPISID" to "sap", "SID" to "sid"),
    )

    @Test
    fun parsesTwoIdentitiesFromDerivedFixture() {
        val client = YoutubeiClient(
            GetTransport(body = Fixtures.load("derived_account_switcher.json"))
        )
        val lookup = client.accountIdentities(session) as IdentityLookup.Found
        assertEquals(2, lookup.identities.size)

        val personal = lookup.identities[0]
        assertEquals("Skyler Example", personal.name)
        assertEquals("skyler@example.com", personal.email)
        // Largest accountPhoto thumbnail wins.
        assertEquals("https://example.com/avatar176.jpg", personal.avatarUrl)
        assertEquals(0, personal.authUser) // from accountNo
        assertNull(personal.pageId)
        assertTrue(personal.isSelected)

        val brand = lookup.identities[1]
        assertEquals("Sentinel Brand Channel", brand.name)
        assertEquals("brand@example.com", brand.email)
        assertEquals("https://example.com/brand88.jpg", brand.avatarUrl)
        assertEquals(1, brand.authUser)
        assertEquals("UCbrandPageId00000001", brand.pageId)
        assertFalse(brand.isSelected)
    }

    @Test
    fun requestGoesToSwitcherEndpointWithAuthHeaders() {
        val transport = GetTransport(body = Fixtures.load("derived_account_switcher.json"))
        YoutubeiClient(transport).accountIdentities(session)
        assertEquals(
            "https://music.youtube.com/getAccountSwitcherEndpoint",
            transport.lastGetUrl
        )
        assertEquals(session.cookieHeader(), transport.lastGetHeaders!!["Cookie"])
        assertTrue(transport.lastGetHeaders!!.containsKey("Authorization"))
        assertEquals("0", transport.lastGetHeaders!!["X-Goog-Authuser"])
        // The switcher endpoint is a web-app XHR: it is called with
        // the web client's presentation, not a bare library UA
        // (2026-10-24 sign-in hunt).
        assertEquals(
            YoutubeiContexts.WEB_REMIX.userAgent,
            transport.lastGetHeaders!!["User-Agent"],
        )
        assertEquals("application/json", transport.lastGetHeaders!!["Accept"])
    }

    @Test
    fun emptyAccountsArrayIsFoundEmpty() {
        val client = YoutubeiClient(
            GetTransport(body = Fixtures.load("derived_account_switcher_empty.json"))
        )
        val lookup = client.accountIdentities(session) as IdentityLookup.Found
        assertTrue(lookup.identities.isEmpty())
    }

    @Test
    fun xssiPrefixedBodyStillParses() {
        val body = ")]}'\n" + Fixtures.load("derived_account_switcher.json")
        val lookup = YoutubeiClient(GetTransport(body = body)).accountIdentities(session)
            as IdentityLookup.Found
        assertEquals(2, lookup.identities.size)
    }

    @Test
    fun unflaggedSelectionDefaultsToFirstIdentity() {
        val body = "{\"accounts\":[" +
            "{\"accountName\":\"First\",\"accountNo\":0}," +
            "{\"accountName\":\"Second\",\"accountNo\":1}]}"
        val lookup = YoutubeiClient(GetTransport(body = body)).accountIdentities(session)
            as IdentityLookup.Found
        assertEquals(2, lookup.identities.size)
        assertTrue(lookup.identities[0].isSelected)
        assertFalse(lookup.identities[1].isSelected)
    }

    @Test
    fun unauthorizedMapsToRejected() {
        val lookup = YoutubeiClient(GetTransport(status = 401, body = ""))
            .accountIdentities(session)
        assertEquals(IdentityLookup.Rejected(401), lookup)
    }

    @Test
    fun forbiddenMapsToRejected() {
        val lookup = YoutubeiClient(GetTransport(status = 403, body = ""))
            .accountIdentities(session)
        assertEquals(IdentityLookup.Rejected(403), lookup)
    }

    @Test
    fun serverErrorMapsToFailed() {
        val lookup = YoutubeiClient(GetTransport(status = 503, body = ""))
            .accountIdentities(session) as IdentityLookup.Failed
        assertTrue(lookup.detail!!.contains("503"))
    }

    @Test
    fun throwingTransportMapsToFailed() {
        val transport = GetTransport(failure = RuntimeException("socket closed"))
        val lookup = YoutubeiClient(transport).accountIdentities(session)
            as IdentityLookup.Failed
        assertTrue(lookup.detail!!.contains("socket closed"))
    }
}
