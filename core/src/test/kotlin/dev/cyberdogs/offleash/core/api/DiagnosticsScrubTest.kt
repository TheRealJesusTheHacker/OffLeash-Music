package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.GetTransport
import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.auth.DeviceCodeResult
import dev.cyberdogs.offleash.core.auth.GoogleDeviceAuth
import dev.cyberdogs.offleash.core.auth.IdentityLookup
import dev.cyberdogs.offleash.core.auth.RefreshResult
import dev.cyberdogs.offleash.core.auth.SessionCookies
import dev.cyberdogs.offleash.core.auth.TokenPollResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostics scrub audit (bug sweep round 2, charter 3): every
 * string that can reach PlaybackDiagnostics / SignInDiagnostics /
 * a stored failure reason / the crash trace is built from class
 * names, statuses and counts — never from a raw exception message,
 * a response body, or a URL. Each test embeds a unique marker in
 * the hostile input and asserts the marker never appears in the
 * resulting diagnostic string.
 */
class DiagnosticsScrubTest {

    private val session = AuthSession(
        cookies = mapOf("SAPISID" to "sap", "SID" to "sid"),
    )

    /** A transport whose form POSTs answer with one fixed response. */
    private class FormTransport(
        private val status: Int,
        private val body: String,
    ) : HttpTransport {
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
            HttpResponse(status, this.body)

        override fun postForm(
            url: String,
            headers: Map<String, String>,
            formBody: String,
        ): HttpResponse = HttpResponse(status, this.body)
    }

    @Test
    fun accountIdentitiesTransportFailureIsClassNameOnly() {
        // This detail is written verbatim into SignInDiagnostics by
        // SessionManager; a real OkHttp failure message embeds the
        // host and address it failed to reach.
        val transport = GetTransport(
            failure = RuntimeException(
                "Failed to connect to https://www.youtube.com/MARKERSECRET:443"
            ),
        )
        val lookup = YoutubeiClient(transport).accountIdentities(session)
            as IdentityLookup.Failed
        assertEquals("transport: RuntimeException", lookup.detail)
        assertFalse(lookup.detail!!.contains("MARKERSECRET"))
        assertFalse(lookup.detail!!.contains("youtube.com"))
    }

    @Test
    fun oauthIdentityTransportFailureIsClassNameOnly() {
        val transport = GetTransport(
            failure = RuntimeException(
                "Failed to connect to https://openidconnect.googleapis.com/MARKERSECRET:443"
            ),
        )
        val lookup = YoutubeiClient(transport).oauthIdentity("token_x")
            as IdentityLookup.Failed
        assertEquals("transport: RuntimeException", lookup.detail)
        assertFalse(lookup.detail!!.contains("MARKERSECRET"))
        assertFalse(lookup.detail!!.contains("googleapis.com"))
    }

    @Test
    fun googleDeviceAuthSurvivesANestingBombBody() {
        // GoogleDeviceAuth's contract is "never throws", but its
        // body parse caught only Exception — a deeply nested body
        // overflows the parser with an Error. Small-stack thread to
        // give the overflow its best chance, like the round-1 pins.
        val sb = StringBuilder()
        repeat(5000) { sb.append("{\"a\":") }
        sb.append("{}")
        repeat(5000) { sb.append("}") }
        val auth = GoogleDeviceAuth(FormTransport(200, sb.toString()))
        var escaped: Throwable? = null
        var device: DeviceCodeResult? = null
        var poll: TokenPollResult? = null
        var refresh: RefreshResult? = null
        val thread = Thread(
            null,
            {
                try {
                    device = auth.requestDeviceCode()
                    poll = auth.pollForToken("device_code_x")
                    refresh = auth.refreshTokens("refresh_x")
                } catch (t: Throwable) {
                    escaped = t
                }
            },
            "oauth-bomb-parse",
            256L * 1024L,
        )
        thread.start()
        thread.join(30_000)
        assertFalse("the calls must have finished", thread.isAlive)
        assertTrue("nothing may escape GoogleDeviceAuth, was $escaped", escaped == null)
        assertTrue(device is DeviceCodeResult.Failed)
        assertTrue(poll is TokenPollResult.Failed)
        assertTrue(refresh is RefreshResult.Failed)
    }

    @Test
    fun pastedCookieBracketBombNeverThrows() {
        // normalizePastedCookie promises totality; its JSON-export
        // probe caught only Exception, so a bracket bomb's parser
        // overflow (an Error) escaped into the sign-in flow.
        val bomb = "[".repeat(50_000) + "]".repeat(50_000)
        var escaped: Throwable? = null
        var shape: String? = null
        val thread = Thread(
            null,
            {
                try {
                    shape = SessionCookies.normalizePastedCookie(bomb).shape.name
                } catch (t: Throwable) {
                    escaped = t
                }
            },
            "paste-bomb",
            256L * 1024L,
        )
        thread.start()
        thread.join(30_000)
        assertFalse("the call must have finished", thread.isAlive)
        assertTrue("nothing may escape normalizePastedCookie, was $escaped", escaped == null)
        assertEquals("COOKIE_HEADER", shape)
    }
}
