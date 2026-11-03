package dev.cyberdogs.offleash.core.auth

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The native authorization-code + PKCE flow (0.8.8, Road C) against
 * a scripted form transport. Response bodies are hand-built to
 * Google's documented OAuth shapes; the PKCE challenge is checked
 * against RFC 7636's own worked example. No real account is
 * involved — the owner's on-device sign-in is the final proof.
 *
 * The contract under test: every protocol answer maps to its named
 * state, state validation refuses returns this attempt didn't
 * start, the exchange presents the verifier and NO secret (Android
 * clients have none), and nothing ever throws.
 */
class GoogleNativeAuthTest {

    /** A transport that serves form POSTs from a script; JSON POSTs are refused. */
    private class ScriptedFormTransport : HttpTransport {
        val responses = ArrayDeque<HttpResponse>()
        var failure: Exception? = null
        var lastUrl: String? = null
            private set
        var lastFormBody: String? = null
            private set

        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
            throw UnsupportedOperationException("this fake serves form POSTs only")

        override fun postForm(
            url: String,
            headers: Map<String, String>,
            formBody: String,
        ): HttpResponse {
            failure?.let { throw it }
            lastUrl = url
            lastFormBody = formBody
            return responses.removeFirst()
        }
    }

    /** Deterministic randomness: fills with bytes 0, 1, 2, … wrapping. */
    private class FixedRandom : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = (i % 251).toByte()
        }
    }

    private val clientId = "123456789012-fakeandroidclientid0000000000ab.apps.googleusercontent.com"
    private val redirectUri = "dev.cyberdogs.offleash.hunt://oauth2redirect"

    private val grantBody = """
        {"access_token":"test-access-token","expires_in":3599,
         "refresh_token":"test-refresh-token","token_type":"Bearer",
         "scope":"https://www.googleapis.com/auth/youtube openid profile email",
         "id_token":"test-id-token"}
    """.trimIndent()

    // ------------------------------------------------------------------
    // PKCE
    // ------------------------------------------------------------------

    @Test
    fun pkceChallengeMatchesTheRfc7636WorkedExample() {
        // RFC 7636 Appendix B: this verifier yields exactly this
        // S256 challenge. The vector is the standard's own, so a
        // drift here means the challenge construction broke.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challengeS256(verifier),
        )
    }

    @Test
    fun generatedVerifierAndStateHaveThePinnedShapes() {
        val random = FixedRandom()
        val verifier = Pkce.newVerifier(random)
        assertEquals(43, verifier.length)
        assertTrue(Pkce.isValidVerifier(verifier))
        val state = Pkce.newState(random)
        assertEquals(22, state.length)
        assertFalse(state.contains('='))
        assertFalse(state.contains('+'))
        assertFalse(state.contains('/'))
    }

    @Test
    fun verifierValidationRejectsBadShapes() {
        assertFalse(Pkce.isValidVerifier("too-short"))
        assertFalse(Pkce.isValidVerifier("a".repeat(129)))
        assertFalse(Pkce.isValidVerifier("a".repeat(42) + " "))
        assertFalse(Pkce.isValidVerifier("a".repeat(42) + "+"))
        assertTrue(Pkce.isValidVerifier("a".repeat(43)))
        assertTrue(Pkce.isValidVerifier("Az09-._~" + "b".repeat(35)))
    }

    // ------------------------------------------------------------------
    // Authorization request
    // ------------------------------------------------------------------

    @Test
    fun beginAuthorizationBuildsTheFullRequest() {
        val auth = GoogleNativeAuth(ScriptedFormTransport(), clientId, FixedRandom())
        val request = auth.beginAuthorization(redirectUri)
        assertTrue("a configured build must produce a request", request != null)
        request!!
        assertEquals(redirectUri, request.redirectUri)
        assertTrue(Pkce.isValidVerifier(request.codeVerifier))
        val url = request.authorizationUrl
        assertTrue(url.startsWith(GoogleOAuth.AUTHORIZATION_URL + "?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=" + clientId.replace(":", "%3A")))
        assertTrue(url.contains("redirect_uri=dev.cyberdogs.offleash.hunt%3A%2F%2Foauth2redirect"))
        assertTrue(url.contains("auth%2Fyoutube"))
        assertTrue(url.contains("state=" + request.state))
        assertTrue(
            url.contains(
                "code_challenge=" + Pkce.challengeS256(request.codeVerifier)
            )
        )
        assertTrue(url.contains("code_challenge_method=S256"))
        // The refresh token's documented price (Google's browser-flow
        // guide): offline access, consented — both ride every request.
        assertTrue(url.contains("access_type=offline"))
        assertTrue(url.contains("prompt=consent"))
        // No secret exists for an Android client; none may be asked for.
        assertFalse(url.contains("client_secret"))
        assertFalse(url.contains("secret"))
    }

    @Test
    fun beginAuthorizationIsNullWhenUnconfigured() {
        val transport = ScriptedFormTransport()
        assertNull(GoogleNativeAuth(transport, null).beginAuthorization(redirectUri))
        assertNull(GoogleNativeAuth(transport, "").beginAuthorization(redirectUri))
        assertNull(GoogleNativeAuth(transport, "   ").beginAuthorization(redirectUri))
    }

    @Test
    fun restoredAttemptRebuildsTheIdenticalUrl() {
        // Process death in the browser leg: the sealed state +
        // verifier must rebuild the very URL the tab opened.
        val auth = GoogleNativeAuth(ScriptedFormTransport(), clientId, FixedRandom())
        val request = auth.beginAuthorization(redirectUri)!!
        val rebuilt = auth.authorizationUrl(
            clientId, request.redirectUri, request.state, request.codeVerifier,
        )
        assertEquals(request.authorizationUrl, rebuilt)
    }

    // ------------------------------------------------------------------
    // Redirect validation
    // ------------------------------------------------------------------

    private fun auth(): GoogleNativeAuth =
        GoogleNativeAuth(ScriptedFormTransport(), clientId, FixedRandom())

    @Test
    fun redirectWithCodeAndMatchingStateIsGranted() {
        val result = auth().parseRedirect(
            "$redirectUri?code=test-auth-code&state=state-1",
            redirectUri, "state-1",
        )
        assertEquals(RedirectResult.Code("test-auth-code"), result)
    }

    @Test
    fun redirectDenialIsNamed() {
        val result = auth().parseRedirect(
            "$redirectUri?error=access_denied&state=state-1",
            redirectUri, "state-1",
        )
        assertEquals(RedirectResult.Denied, result)
    }

    @Test
    fun redirectWithForeignStateIsRefused() {
        // The CSRF binding: a return this attempt didn't start —
        // however valid its code — is not this attempt's to collect.
        val result = auth().parseRedirect(
            "$redirectUri?code=test-auth-code&state=someone-elses-state",
            redirectUri, "state-1",
        )
        assertEquals(RedirectResult.Failed("state_mismatch"), result)
    }

    @Test
    fun redirectWithoutStateIsRefused() {
        val result = auth().parseRedirect(
            "$redirectUri?code=test-auth-code",
            redirectUri, "state-1",
        )
        assertEquals(RedirectResult.Failed("state_mismatch"), result)
    }

    @Test
    fun redirectToTheWrongDestinationIsRefusedBeforeParsing() {
        val result = auth().parseRedirect(
            "dev.cyberdogs.offleash.hunt://phishing?code=x&state=state-1",
            redirectUri, "state-1",
        )
        assertEquals(
            RedirectResult.Failed("unexpected_redirect_destination"), result,
        )
    }

    @Test
    fun redirectWithoutCodeOrErrorIsMalformed() {
        val noAnswer = auth().parseRedirect(redirectUri, redirectUri, "state-1")
        assertTrue(noAnswer is RedirectResult.Failed)
        val noCode = auth().parseRedirect(
            "$redirectUri?state=state-1", redirectUri, "state-1",
        )
        assertEquals(RedirectResult.Failed("redirect carried no code"), noCode)
    }

    @Test
    fun redirectErrorTextIsAllowListed() {
        val known = auth().parseRedirect(
            "$redirectUri?error=invalid_scope&state=state-1",
            redirectUri, "state-1",
        )
        assertEquals(
            RedirectResult.Failed("redirect error: invalid_scope"), known,
        )
        // Endpoint-controlled free text never reaches a detail: an
        // unlisted "error" reads as the generic replacement, so it
        // can't inject lines into diagnostics or a user-facing card.
        val hostile = auth().parseRedirect(
            "$redirectUri?error=weird%0Ainjected-line&state=state-1",
            redirectUri, "state-1",
        )
        assertEquals(
            RedirectResult.Failed("redirect error: unrecognized error"), hostile,
        )
    }

    // ------------------------------------------------------------------
    // Code exchange
    // ------------------------------------------------------------------

    @Test
    fun exchangePresentsVerifierAndNoSecret() {
        val transport = ScriptedFormTransport()
        transport.responses.add(HttpResponse(200, grantBody))
        val auth = GoogleNativeAuth(transport, clientId, FixedRandom())
        val result = auth.exchangeCode("test-auth-code", "test-verifier", redirectUri)
        val granted = result as CodeExchangeResult.Granted
        assertEquals("test-access-token", granted.tokens.accessToken)
        assertEquals("test-refresh-token", granted.tokens.refreshToken)
        assertEquals(3599L, granted.tokens.expiresInSeconds)

        assertEquals(GoogleOAuth.TOKEN_URL, transport.lastUrl)
        val form = transport.lastFormBody!!
        assertTrue(form.contains("client_id=$clientId"))
        assertTrue(form.contains("code=test-auth-code"))
        assertTrue(form.contains("code_verifier=test-verifier"))
        assertTrue(form.contains("grant_type=authorization_code"))
        assertTrue(form.contains("redirect_uri=dev.cyberdogs.offleash.hunt%3A%2F%2Foauth2redirect"))
        assertFalse(form.contains("client_secret"))
        assertFalse(form.contains("secret"))
    }

    @Test
    fun exchangeMapsRefusalsToNamedFailures() {
        val transport = ScriptedFormTransport()
        val auth = GoogleNativeAuth(transport, clientId, FixedRandom())

        transport.responses.add(HttpResponse(400, """{"error":"invalid_grant"}"""))
        val dead = auth.exchangeCode("dead-code", "v", redirectUri)
        assertEquals(
            CodeExchangeResult.Failed("HTTP 400: invalid_grant"), dead,
        )

        transport.responses.add(HttpResponse(401, """{"error":"invalid_client"}"""))
        val wrongClient = auth.exchangeCode("code", "v", redirectUri)
        assertEquals(
            CodeExchangeResult.Failed("HTTP 401: invalid_client"), wrongClient,
        )

        transport.failure = RuntimeException("boom")
        val transportFailure = auth.exchangeCode("code", "v", redirectUri)
        assertEquals(
            CodeExchangeResult.Failed("transport: RuntimeException"),
            transportFailure,
        )
    }

    @Test
    fun exchangeWithoutAccessTokenIsAFailure() {
        val transport = ScriptedFormTransport()
        transport.responses.add(HttpResponse(200, """{"expires_in":3599}"""))
        val auth = GoogleNativeAuth(transport, clientId, FixedRandom())
        assertEquals(
            CodeExchangeResult.Failed("grant carried no access token"),
            auth.exchangeCode("code", "v", redirectUri),
        )
    }

    @Test
    fun exchangeIsHonestlyUnconfiguredWithoutAClientId() {
        val transport = ScriptedFormTransport()
        val auth = GoogleNativeAuth(transport, null)
        assertEquals(
            CodeExchangeResult.Failed("client_not_configured"),
            auth.exchangeCode("code", "v", redirectUri),
        )
        assertEquals(
            RefreshResult.Failed("client_not_configured"),
            auth.refreshTokens("rt"),
        )
    }

    // ------------------------------------------------------------------
    // Refresh + revoke
    // ------------------------------------------------------------------

    @Test
    fun refreshPresentsTheRefreshTokenAndNoSecret() {
        val transport = ScriptedFormTransport()
        transport.responses.add(
            HttpResponse(
                200,
                """{"access_token":"fresh-access-token","expires_in":3599}""",
            )
        )
        val auth = GoogleNativeAuth(transport, clientId, FixedRandom())
        val result = auth.refreshTokens("test-refresh-token")
        val refreshed = result as RefreshResult.Refreshed
        assertEquals("fresh-access-token", refreshed.tokens.accessToken)
        // Google does not rotate a refresh token on every refresh:
        // a body without one reads as null (the caller keeps its own).
        assertNull(refreshed.tokens.refreshToken)
        val form = transport.lastFormBody!!
        assertTrue(form.contains("client_id=$clientId"))
        assertTrue(form.contains("refresh_token=test-refresh-token"))
        assertTrue(form.contains("grant_type=refresh_token"))
        assertFalse(form.contains("secret"))
    }

    @Test
    fun refreshMapsInvalidGrantToRevoked() {
        val transport = ScriptedFormTransport()
        transport.responses.add(HttpResponse(400, """{"error":"invalid_grant"}"""))
        val auth = GoogleNativeAuth(transport, clientId, FixedRandom())
        assertEquals(RefreshResult.Revoked, auth.refreshTokens("dead-rt"))
    }

    @Test
    fun revokeIsBestEffortAndNamesNoClient() {
        val transport = ScriptedFormTransport()
        transport.responses.add(HttpResponse(200, ""))
        // Revocation names no client, so even an unconfigured build
        // can end a standing grant at Google.
        val auth = GoogleNativeAuth(transport, null)
        assertTrue(auth.revokeToken("some-token"))
        assertTrue(transport.lastFormBody!!.contains("token=some-token"))
        transport.failure = RuntimeException("offline")
        assertFalse(auth.revokeToken("some-token"))
    }

    // ------------------------------------------------------------------
    // The client-change gate, native class
    // ------------------------------------------------------------------

    @Test
    fun gateCarriesAndroidUnderAndroid() {
        assertEquals(
            ClientGateDecision.CARRY,
            ClientChangeGate.decide("android", OAuthClientKind.ANDROID),
        )
    }

    @Test
    fun gateEndsDeviceEraSessionsUnderAnAndroidBuild() {
        assertEquals(
            ClientGateDecision.CLEAN_RESIGN,
            ClientChangeGate.decide("owner", OAuthClientKind.ANDROID),
        )
        assertEquals(
            ClientGateDecision.CLEAN_RESIGN,
            ClientChangeGate.decide("shared", OAuthClientKind.ANDROID),
        )
        // Pre-marker sessions read as the shared class.
        assertEquals(
            ClientGateDecision.CLEAN_RESIGN,
            ClientChangeGate.decide(null, OAuthClientKind.ANDROID),
        )
    }

    @Test
    fun gateEndsAndroidSessionsUnderAnythingElse() {
        assertEquals(
            ClientGateDecision.CLEAN_RESIGN,
            ClientChangeGate.decide("android", OAuthClientKind.OWNER),
        )
        assertEquals(
            ClientGateDecision.CLEAR_UNCONFIGURED,
            ClientChangeGate.decide("android", null),
        )
    }

    @Test
    fun signInClientLineNamesTheAndroidClass() {
        assertEquals("client=android", signInClientLine(OAuthClientKind.ANDROID))
    }
}
