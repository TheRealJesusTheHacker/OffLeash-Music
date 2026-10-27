package dev.cyberdogs.offleash.core.auth

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Google device-code flow (0.7.0 sign-in) against a scripted
 * form transport. The response bodies below are hand-built to
 * Google's documented OAuth shapes (RFC 8628 device flow + the
 * token endpoint's error vocabulary) — no real account is involved;
 * the live structural probe of these endpoints (a real code issued,
 * a real `authorization_pending` returned) is recorded in the build
 * report, and the owner's on-device approval is the final proof.
 *
 * The contract under test: the state machine maps every protocol
 * answer to its named state, and NOTHING ever throws out of it.
 */
class GoogleDeviceAuthTest {

    /** A transport that serves form POSTs from a script; JSON POSTs are refused. */
    private class ScriptedFormTransport : HttpTransport {
        val responses = ArrayDeque<HttpResponse>()
        var failure: Exception? = null
        var lastUrl: String? = null
            private set
        var lastFormBody: String? = null
            private set
        var lastHeaders: Map<String, String>? = null
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
            lastHeaders = headers
            return responses.removeFirst()
        }
    }

    private val issuedBody = """
        {"device_code":"device-code-abc","user_code":"WDJB-MJHT",
         "verification_url":"https://www.google.com/device",
         "expires_in":1800,"interval":5}
    """.trimIndent()

    private val grantBody = """
        {"access_token":"ya29.test-access","expires_in":3599,
         "refresh_token":"test-refresh-token","token_type":"Bearer",
         "scope":"https://www.googleapis.com/auth/youtube openid profile email",
         "id_token":"test-id-token"}
    """.trimIndent()

    @Test
    fun deviceCodeIssuanceParsesAndRequestsThePinnedScopes() {
        val transport = ScriptedFormTransport()
        transport.responses.add(HttpResponse(200, issuedBody))
        val result = GoogleDeviceAuth(transport).requestDeviceCode()
        val issued = result as DeviceCodeResult.Issued
        assertEquals("device-code-abc", issued.code.deviceCode)
        assertEquals("WDJB-MJHT", issued.code.userCode)
        assertEquals("https://www.google.com/device", issued.code.verificationUrl)
        assertEquals(5L, issued.code.intervalSeconds)
        assertEquals(1800L, issued.code.expiresInSeconds)

        assertEquals(GoogleOAuth.DEVICE_CODE_URL, transport.lastUrl)
        val form = transport.lastFormBody!!
        assertTrue(form.contains("client_id=${GoogleOAuth.CLIENT_ID}"))
        // The scope rides form-encoded: the youtube scope's slashes
        // are percent-encoded and the spaces became '+'.
        assertTrue(form.contains("auth%2Fyoutube"))
        assertTrue(form.contains("openid"))
        assertEquals(
            "application/x-www-form-urlencoded",
            transport.lastHeaders!!["Content-Type"],
        )
    }

    @Test
    fun deviceCodeIssuanceFailureIsAValueNotAThrow() {
        val transport = ScriptedFormTransport()
        transport.responses.add(
            HttpResponse(400, """{"error":"invalid_client","error_description":"bad"}""")
        )
        val result = GoogleDeviceAuth(transport).requestDeviceCode()
        val failed = result as DeviceCodeResult.Failed
        assertTrue(failed.detail.contains("invalid_client"))

        val deadTransport = ScriptedFormTransport()
        deadTransport.failure = RuntimeException("connection reset")
        val dead = GoogleDeviceAuth(deadTransport).requestDeviceCode()
        assertTrue(dead is DeviceCodeResult.Failed)
    }

    @Test
    fun pollMapsTheProtocolAnswersByTheBook() {
        fun pollWith(response: HttpResponse): TokenPollResult {
            val transport = ScriptedFormTransport()
            transport.responses.add(response)
            return GoogleDeviceAuth(transport).pollForToken("device-code-abc")
        }
        // Pending arrives as an error body (400 in the docs; the live
        // probe answered 428) — the mapping keys on the error code.
        assertTrue(
            pollWith(HttpResponse(400, """{"error":"authorization_pending"}"""))
                is TokenPollResult.Pending
        )
        assertTrue(
            pollWith(HttpResponse(428, """{"error":"authorization_pending"}"""))
                is TokenPollResult.Pending
        )
        assertTrue(
            pollWith(HttpResponse(400, """{"error":"slow_down"}"""))
                is TokenPollResult.SlowDown
        )
        assertTrue(
            pollWith(HttpResponse(400, """{"error":"access_denied"}"""))
                is TokenPollResult.Denied
        )
        assertTrue(
            pollWith(HttpResponse(400, """{"error":"expired_token"}"""))
                is TokenPollResult.Expired
        )
        val failed = pollWith(HttpResponse(500, """{"error":"server_error"}"""))
        assertTrue(failed is TokenPollResult.Failed)
    }

    @Test
    fun pollGrantParsesTheTokenSetAndSendsTheDeviceGrant() {
        val transport = ScriptedFormTransport()
        transport.responses.add(HttpResponse(200, grantBody))
        val result = GoogleDeviceAuth(transport).pollForToken("device-code-abc")
        val granted = result as TokenPollResult.Granted
        assertEquals("ya29.test-access", granted.tokens.accessToken)
        assertEquals("test-refresh-token", granted.tokens.refreshToken)
        assertEquals(3599L, granted.tokens.expiresInSeconds)
        assertEquals("Bearer", granted.tokens.tokenType)

        assertEquals(GoogleOAuth.TOKEN_URL, transport.lastUrl)
        val form = transport.lastFormBody!!
        assertTrue(form.contains("device_code=device-code-abc"))
        assertTrue(
            form.contains(
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"
            )
        )
        assertTrue(form.contains("client_id=${GoogleOAuth.CLIENT_ID}"))
    }

    @Test
    fun pollTransportFailureIsAValueNotAThrow() {
        val transport = ScriptedFormTransport()
        transport.failure = RuntimeException("timeout")
        val result = GoogleDeviceAuth(transport).pollForToken("device-code-abc")
        assertTrue(result is TokenPollResult.Failed)
    }

    @Test
    fun refreshParsesANewAccessToken() {
        val transport = ScriptedFormTransport()
        transport.responses.add(
            HttpResponse(200, """{"access_token":"ya29.fresh","expires_in":3599,"token_type":"Bearer"}""")
        )
        val result = GoogleDeviceAuth(transport).refreshTokens("test-refresh-token")
        val refreshed = result as RefreshResult.Refreshed
        assertEquals("ya29.fresh", refreshed.tokens.accessToken)
        // A refresh answer that doesn't rotate the refresh token
        // reports null for it — the caller keeps the stored one.
        assertEquals(null, refreshed.tokens.refreshToken)
        assertTrue(transport.lastFormBody!!.contains("grant_type=refresh_token"))
        assertTrue(transport.lastFormBody!!.contains("refresh_token=test-refresh-token"))
    }

    @Test
    fun refreshInvalidGrantReadsAsRevoked() {
        val transport = ScriptedFormTransport()
        transport.responses.add(
            HttpResponse(400, """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""")
        )
        val result = GoogleDeviceAuth(transport).refreshTokens("test-refresh-token")
        assertTrue(result is RefreshResult.Revoked)
    }

    @Test
    fun refreshServerTroubleIsAFailureNotARevocation() {
        val transport = ScriptedFormTransport()
        transport.responses.add(HttpResponse(503, """{"error":"temporarily_unavailable"}"""))
        val result = GoogleDeviceAuth(transport).refreshTokens("test-refresh-token")
        assertTrue(result is RefreshResult.Failed)
    }

    @Test
    fun revokeReportsGooglesAnswerAndNeverThrows() {
        val transport = ScriptedFormTransport()
        transport.responses.add(HttpResponse(200, ""))
        assertTrue(GoogleDeviceAuth(transport).revokeToken("test-refresh-token"))
        assertEquals(GoogleOAuth.REVOKE_URL, transport.lastUrl)
        assertTrue(transport.lastFormBody!!.contains("token=test-refresh-token"))

        val deadTransport = ScriptedFormTransport()
        deadTransport.failure = RuntimeException("offline")
        assertEquals(false, GoogleDeviceAuth(deadTransport).revokeToken("x"))
    }
}
