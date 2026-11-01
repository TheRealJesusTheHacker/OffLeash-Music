package dev.cyberdogs.offleash.core.auth

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 0.8.6 own-client machinery, proven at the seam the hunts
 * can prove: pair resolution (present / absent / malformed), the
 * client-change gate's whole decision matrix, the scope string's
 * exactness against the decision brief's recipe, the diagnostics
 * class line, and the unconfigured build's honest first-call
 * failures. Whether Google accepts an owner-issued token on the
 * library surfaces is the device question the brief's kill
 * criteria govern — not this suite's claim.
 */
class OAuthClientIdentityTest {

    // ---- resolve(): the supply shapes ---------------------------

    @Test
    fun resolveAcceptsACompleteOwnerPair() {
        val resolved = OAuthClientIdentity.resolve(
            "  fake-owner-client-id  ",
            "fake-owner-client-secret",
            "owner",
        )
        assertEquals(
            OAuthClientResolution.Configured(TestOAuthIdentity.OWNER),
            resolved,
        )
    }

    @Test
    fun resolveAcceptsACompleteSharedPair() {
        val resolved = OAuthClientIdentity.resolve(
            "fake-shared-client-id",
            "fake-shared-client-secret",
            "shared",
        )
        assertEquals(
            OAuthClientResolution.Configured(TestOAuthIdentity.SHARED),
            resolved,
        )
    }

    @Test
    fun resolveReadsAnAllBlankSupplyAsAbsent() {
        assertEquals(
            OAuthClientResolution.Absent,
            OAuthClientIdentity.resolve(null, null, null),
        )
        assertEquals(
            OAuthClientResolution.Absent,
            OAuthClientIdentity.resolve("", "  ", ""),
        )
    }

    @Test
    fun resolveReadsAHalfPairAsMalformed() {
        val idOnly = OAuthClientIdentity.resolve("some-id", null, "owner")
        assertTrue(idOnly is OAuthClientResolution.Malformed)
        val secretOnly = OAuthClientIdentity.resolve(null, "some-secret", "owner")
        assertTrue(secretOnly is OAuthClientResolution.Malformed)
    }

    @Test
    fun resolveReadsAPairWithoutAClassAsMalformed() {
        val resolved = OAuthClientIdentity.resolve("some-id", "some-secret", null)
        assertTrue(resolved is OAuthClientResolution.Malformed)
    }

    @Test
    fun resolveReadsAClassWithoutAPairAsMalformed() {
        val resolved = OAuthClientIdentity.resolve(null, null, "owner")
        assertTrue(resolved is OAuthClientResolution.Malformed)
    }

    @Test
    fun resolveReadsAnUnknownClassAsMalformed() {
        val resolved = OAuthClientIdentity.resolve(
            "some-id",
            "some-secret",
            "borrowed",
        )
        assertTrue(resolved is OAuthClientResolution.Malformed)
    }

    // ---- the gate's decision matrix -----------------------------

    @Test
    fun gateCarriesASessionSealedUnderTheSameClass() {
        assertEquals(
            ClientGateDecision.CARRY,
            ClientChangeGate.decide("owner", OAuthClientKind.OWNER),
        )
        assertEquals(
            ClientGateDecision.CARRY,
            ClientChangeGate.decide("shared", OAuthClientKind.SHARED),
        )
        // A pre-0.8.6 sealed session carries no marker: it reads
        // as shared — the only issuer it can have had — and so
        // carries under a shared build untouched.
        assertEquals(
            ClientGateDecision.CARRY,
            ClientChangeGate.decide(null, OAuthClientKind.SHARED),
        )
    }

    @Test
    fun gateCleanlyResignsASessionSealedUnderAnotherClass() {
        // The 0.8.6 upgrade itself: every standing session was
        // sealed under the shared pair (marker absent) and the
        // owner build is now the live class.
        assertEquals(
            ClientGateDecision.CLEAN_RESIGN,
            ClientChangeGate.decide(null, OAuthClientKind.OWNER),
        )
        assertEquals(
            ClientGateDecision.CLEAN_RESIGN,
            ClientChangeGate.decide("shared", OAuthClientKind.OWNER),
        )
        assertEquals(
            ClientGateDecision.CLEAN_RESIGN,
            ClientChangeGate.decide("owner", OAuthClientKind.SHARED),
        )
        // An unrecognized marker is not the owner's class either.
        assertEquals(
            ClientGateDecision.CLEAN_RESIGN,
            ClientChangeGate.decide("something-else", OAuthClientKind.OWNER),
        )
    }

    @Test
    fun gateClearsAnySessionWhenTheBuildHasNoClient() {
        assertEquals(
            ClientGateDecision.CLEAR_UNCONFIGURED,
            ClientChangeGate.decide("owner", null),
        )
        assertEquals(
            ClientGateDecision.CLEAR_UNCONFIGURED,
            ClientChangeGate.decide("shared", null),
        )
        assertEquals(
            ClientGateDecision.CLEAR_UNCONFIGURED,
            ClientChangeGate.decide(null, null),
        )
    }

    // ---- scope exactness -----------------------------------------

    @Test
    fun scopeLeadsWithTheBriefsExactYouTubeScope() {
        assertEquals(
            "https://www.googleapis.com/auth/youtube",
            GoogleOAuth.YOUTUBE_SCOPE,
        )
        // The requested scope's first token is the recipe's scope,
        // exactly — no readonly variant, no extra YouTube scope.
        assertEquals(
            GoogleOAuth.YOUTUBE_SCOPE,
            GoogleOAuth.SCOPE.split(" ").first(),
        )
        assertTrue(!GoogleOAuth.SCOPE.contains("readonly"))
        assertEquals(
            "https://www.googleapis.com/auth/youtube openid profile email",
            GoogleOAuth.SCOPE,
        )
    }

    // ---- the diagnostics class line ------------------------------

    @Test
    fun clientLineNamesTheClassOnly() {
        assertEquals("client=owner", signInClientLine(OAuthClientKind.OWNER))
        assertEquals("client=shared", signInClientLine(OAuthClientKind.SHARED))
        assertEquals("client=unconfigured", signInClientLine(null))
    }

    // ---- the unconfigured build fails honestly -------------------

    /** A transport that must never be reached by these calls. */
    private class NoCallTransport : HttpTransport {
        var calls = 0

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse {
            calls++
            return HttpResponse(200, "{}")
        }

        override fun postForm(
            url: String,
            headers: Map<String, String>,
            formBody: String,
        ): HttpResponse {
            calls++
            return HttpResponse(200, "{}")
        }

        override fun get(
            url: String,
            headers: Map<String, String>,
        ): HttpResponse {
            calls++
            return HttpResponse(200, "{}")
        }
    }

    @Test
    fun unconfiguredAuthFailsClientBoundCallsWithoutTouchingTheWire() {
        val transport = NoCallTransport()
        val auth = GoogleDeviceAuth(transport, null)
        assertEquals(
            DeviceCodeResult.Failed("client_not_configured"),
            auth.requestDeviceCode(),
        )
        assertEquals(
            TokenPollResult.Failed("client_not_configured"),
            auth.pollForToken("device-code-abc"),
        )
        assertEquals(
            RefreshResult.Failed("client_not_configured"),
            auth.refreshTokens("test-refresh-token"),
        )
        assertEquals(0, transport.calls)
    }

    @Test
    fun identitySwapAtRuntimeChangesWhichClientAsks() {
        // The wizard's switch, at core level: the same auth object
        // asks as the shared pair, then as the owner's pair, and
        // the wire sees each in turn.
        val bodies = mutableListOf<String>()
        val transport = object : HttpTransport {
            override fun post(
                url: String,
                headers: Map<String, String>,
                body: String,
            ): HttpResponse = throw UnsupportedOperationException("form only")

            override fun postForm(
                url: String,
                headers: Map<String, String>,
                formBody: String,
            ): HttpResponse {
                bodies.add(formBody)
                return HttpResponse(
                    200,
                    """{"device_code":"dc","user_code":"UC",""" +
                        """"verification_url":"https://www.google.com/device",""" +
                        """"expires_in":1800,"interval":5}""",
                )
            }

            override fun get(
                url: String,
                headers: Map<String, String>,
            ): HttpResponse = throw UnsupportedOperationException("form only")
        }
        val auth = GoogleDeviceAuth(transport, TestOAuthIdentity.SHARED)
        auth.requestDeviceCode()
        auth.identity = TestOAuthIdentity.OWNER
        auth.requestDeviceCode()
        assertTrue(bodies[0].contains("client_id=fake-shared-client-id"))
        assertTrue(bodies[1].contains("client_id=fake-owner-client-id"))
    }
}
