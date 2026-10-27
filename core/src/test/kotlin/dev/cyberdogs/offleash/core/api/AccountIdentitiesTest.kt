package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.Fixtures
import dev.cyberdogs.offleash.core.GetTransport
import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.auth.IdentityLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account-switcher parsing and outcome mapping (Phase 2).
 *
 * The `derived_*.json` fixtures are DERIVED-SYNTHETIC: no real account
 * capture exists in this project, and no real cookie or identity
 * appears in them. Their SHAPE, however, is not invented: it follows,
 * field for field, the paths two independent production parsers of
 * this endpoint read — Rehike's `Switcher::parseResponse` and
 * `SignInV2\Parser\SwitcherParser`, which consume the live
 * `getAccountSwitcherEndpoint` response: a menu structure under
 * `data.actions[0].getMultiPageMenuAction`, NOT a flat
 * `{"accounts": [...]}` list.
 *
 * History (2026-10-27 sign-in hunt): the pre-hunt fixture encoded a
 * hand-guessed flat shape, the parser was written to it, and every
 * test passed — while on the owner's phone the real menu-shaped
 * response parsed to zero identities on every attempt and sign-in
 * could never confirm. The endpoint had been answering correctly
 * all along; the parser was reading a shape that never occurs.
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
        assertEquals(0, personal.authUser) // from the signin token's authuser
        assertNull(personal.pageId) // the personal identity carries no page token
        assertFalse(personal.isSelected)

        val brand = lookup.identities[1]
        assertEquals("Sentinel Brand Channel", brand.name)
        // The email is the section's Google account email; brand
        // identities of one Google account share it.
        assertEquals("skyler@example.com", brand.email)
        assertEquals("https://example.com/brand88.jpg", brand.avatarUrl)
        // Same Google account: the SAME authuser as the personal
        // identity. The pageId token is what distinguishes it.
        assertEquals(0, brand.authUser)
        assertEquals("987654321098765432109", brand.pageId)
        assertTrue(brand.isSelected)
    }

    @Test
    fun requestGoesToSwitcherEndpointWithAuthHeaders() {
        val transport = GetTransport(body = Fixtures.load("derived_account_switcher.json"))
        YoutubeiClient(transport).accountIdentities(session)
        assertEquals(
            "https://www.youtube.com/getAccountSwitcherEndpoint",
            transport.lastGetUrl
        )
        assertEquals(session.cookieHeader(), transport.lastGetHeaders!!["Cookie"])
        // The SAPISIDHASH origin must name the host the request is
        // actually sent to (www.youtube.com for the switcher — the
        // construction the endpoint's own web surface uses), NOT the
        // music.youtube.com origin the youtubei calls use. Pin the
        // full header value: recover the timestamp the client used
        // from the recorded header and recompute the digest for the
        // www origin.
        val authorization = transport.lastGetHeaders!!["Authorization"]!!
        val timestamp = authorization
            .removePrefix("SAPISIDHASH ")
            .substringBefore('_')
            .toLong()
        assertEquals(
            session.authorizationHeader(timestamp, "https://www.youtube.com"),
            authorization,
        )
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
    fun emptySwitcherResponseIsFoundEmpty() {
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
        // The derived fixture with every isSelected flag stripped:
        // the endpoint's no-flag answer must still yield a selected
        // default (the first identity), or SessionManager would have
        // nothing to adopt.
        val body = Fixtures.load("derived_account_switcher.json")
            .replace("\"isSelected\": true, ", "")
            .replace("\"isSelected\": false, ", "")
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

    // ------------------------------------------------------------------
    // Crash-hunt variants (2026-10-27): the shapes the lab fixture
    // never modeled, pinned permanently. The 0.6.3 parser was proven
    // total against every one of these in the hunt harness — they are
    // in the suite so totality is certified, not remembered. (The
    // device crash itself was downstream of the parse; see
    // SignInConfirmationTest's adoption-failure tests.)
    // ------------------------------------------------------------------

    @Test
    fun multiAccountMenuParsesEverySection() {
        val client = YoutubeiClient(
            GetTransport(body = Fixtures.load("derived_account_switcher_multi.json"))
        )
        val lookup = client.accountIdentities(session) as IdentityLookup.Found
        assertEquals(4, lookup.identities.size)

        val first = lookup.identities[0]
        assertEquals("First Listener", first.name)
        assertEquals("first@example.com", first.email)
        assertEquals(0, first.authUser)
        assertNull(first.pageId)
        assertTrue(first.isSelected)

        val secondPersonal = lookup.identities[1]
        assertEquals("Second Listener", secondPersonal.name)
        assertEquals("second@example.com", secondPersonal.email)
        assertEquals(1, secondPersonal.authUser)
        assertFalse(secondPersonal.isSelected)

        val secondBrand = lookup.identities[2]
        assertEquals("Second Brand Channel", secondBrand.name)
        assertEquals("second@example.com", secondBrand.email)
        // The brand identity shares its Google account's index; the
        // pageId is what distinguishes it.
        assertEquals(1, secondBrand.authUser)
        assertEquals("555444333222111000999", secondBrand.pageId)
        assertTrue(secondBrand.isSelected)

        val thirdBrand = lookup.identities[3]
        assertEquals("third@example.com", thirdBrand.email)
        assertEquals(2, thirdBrand.authUser)
        assertEquals("777888999000111222333", thirdBrand.pageId)
        assertTrue(thirdBrand.isSelected)
    }

    @Test
    fun foundCarriesShapeSummary() {
        val lookup = YoutubeiClient(
            GetTransport(body = Fixtures.load("derived_account_switcher_multi.json"))
        ).accountIdentities(session) as IdentityLookup.Found
        val summary = lookup.shapeSummary
        assertNotNull(summary)
        assertTrue(summary!!.contains("3 account section(s)"))
        assertTrue(summary.contains("4 identity item(s)"))
    }

    @Test
    fun itemWithoutEndpointOrPhotoStillParses() {
        val body = """{"data":{"actions":[{"getMultiPageMenuAction":{"menu":{"multiPageMenuRenderer":{"sections":[{"accountSectionListRenderer":{"header":{"googleAccountHeaderRenderer":{"email":{"simpleText":"bare@example.com"}}},"contents":[{"accountItemSectionRenderer":{"contents":[{"accountItem":{"accountName":{"simpleText":"Bare"},"isSelected":true}}]}}]}}]}}}}]}}"""
        val lookup = YoutubeiClient(GetTransport(body = body)).accountIdentities(session)
            as IdentityLookup.Found
        assertEquals(1, lookup.identities.size)
        val only = lookup.identities[0]
        assertEquals("Bare", only.name)
        assertEquals("bare@example.com", only.email)
        assertNull(only.avatarUrl)
        assertNull(only.pageId)
        // No sign-in token to read the index from: the section's
        // position is the fallback (the only section → 0).
        assertEquals(0, only.authUser)
        assertTrue(only.isSelected)
    }

    @Test
    fun stringSerializedSelectedFlagIsReadLeniently() {
        val body = """{"data":{"actions":[{"getMultiPageMenuAction":{"menu":{"multiPageMenuRenderer":{"sections":[{"accountSectionListRenderer":{"header":{"googleAccountHeaderRenderer":{"email":{"simpleText":"s@example.com"}}},"contents":[{"accountItemSectionRenderer":{"contents":[{"accountItem":{"accountName":{"simpleText":"First"},"isSelected":false}},{"accountItem":{"accountName":{"simpleText":"Second"},"isSelected":"true"}}]}}]}}]}}}}]}}"""
        val lookup = YoutubeiClient(GetTransport(body = body)).accountIdentities(session)
            as IdentityLookup.Found
        assertEquals(2, lookup.identities.size)
        // The endpoint's flag is a JSON boolean; should a surface
        // ever serialize it as the string "true", the JSON layer's
        // lenient boolean read still lands the selection on the
        // flagged identity — the semantics survive the encoding.
        assertFalse(lookup.identities[0].isSelected)
        assertTrue(lookup.identities[1].isSelected)
    }

    @Test
    fun interleavedUnknownRenderersAreSkipped() {
        val body = """{"data":{"actions":[{"getMultiPageMenuAction":{"menu":{"multiPageMenuRenderer":{"sections":[{"accountSectionListRenderer":{"header":{"googleAccountHeaderRenderer":{"email":{"simpleText":"mix@example.com"}}},"contents":[{"accountItemSectionRenderer":{"contents":[{"promoRenderer":{"text":"x"}},{"accountItem":{"accountName":{"simpleText":"Kept"},"isSelected":true}},{"continuationItemRenderer":{"continuationEndpoint":{}}}]}}]}}]}}}}]}}"""
        val lookup = YoutubeiClient(GetTransport(body = body)).accountIdentities(session)
            as IdentityLookup.Found
        assertEquals(1, lookup.identities.size)
        assertEquals("Kept", lookup.identities[0].name)
    }

    @Test
    fun htmlBodyFailsInsteadOfThrowing() {
        val lookup = YoutubeiClient(
            GetTransport(body = "<html><body>consent page</body></html>")
        ).accountIdentities(session) as IdentityLookup.Failed
        assertTrue(lookup.detail!!.contains("not valid JSON"))
    }
}
