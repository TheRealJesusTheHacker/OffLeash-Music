package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.api.HttpResponse
import dev.cyberdogs.offleash.core.api.HttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The Bearer retry decorator's contract (sign-in perfection pass):
 * exactly one second chance for a refused Bearer token, nothing
 * for anything else. The delegate is a scripted recorder — the
 * wire never leaves the JVM.
 */
class BearerRetryTransportTest {

    /** Records every call and answers from a per-method script. */
    private class RecordingTransport : HttpTransport {
        data class Call(
            val method: String,
            val url: String,
            val headers: Map<String, String>,
            val body: String?,
        )

        val calls = mutableListOf<Call>()
        val responses = ArrayDeque<HttpResponse>()

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse {
            calls += Call("POST", url, headers, body)
            return responses.removeFirst()
        }

        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            calls += Call("GET", url, headers, null)
            return responses.removeFirst()
        }

        override fun postForm(
            url: String,
            headers: Map<String, String>,
            formBody: String,
        ): HttpResponse {
            calls += Call("POST_FORM", url, headers, formBody)
            return responses.removeFirst()
        }
    }

    private fun transport(
        delegate: RecordingTransport,
        hook: (String) -> String?,
    ): BearerRetryTransport = BearerRetryTransport(delegate, hook)

    @Test
    fun successPassesThroughAndNeverConsultsTheHook() {
        val delegate = RecordingTransport()
        val ok = HttpResponse(200, "{}")
        delegate.responses.add(ok)
        var hookCalls = 0
        val response = transport(delegate) { hookCalls++; null }
            .post(URL, mapOf("Authorization" to "Bearer ya29.live"), "{}")
        assertSame(ok, response)
        assertEquals(1, delegate.calls.size)
        assertEquals(0, hookCalls)
    }

    @Test
    fun unauthorizedWithoutAnAuthorizationHeaderPassesThrough() {
        val delegate = RecordingTransport()
        val refused = HttpResponse(401, "who are you")
        delegate.responses.add(refused)
        var hookCalls = 0
        val response = transport(delegate) { hookCalls++; "ya29.fresh" }
            .get(URL, emptyMap())
        assertSame(refused, response)
        assertEquals(1, delegate.calls.size)
        assertEquals(0, hookCalls)
    }

    @Test
    fun theCookieRoadsSapisidhashAuthorizationIsNeverRetried() {
        // The cookie session's Authorization value is a SAPISIDHASH,
        // not a Bearer token: a 401 there is the cookie road's own
        // business (its validation path), and a Google-token refresh
        // must never be spent on it.
        val delegate = RecordingTransport()
        val refused = HttpResponse(401, "session refused")
        delegate.responses.add(refused)
        var hookCalls = 0
        val response = transport(delegate) { hookCalls++; "ya29.fresh" }
            .post(URL, mapOf("Authorization" to "SAPISIDHASH 1234_abcd"), "{}")
        assertSame(refused, response)
        assertEquals(1, delegate.calls.size)
        assertEquals(0, hookCalls)
    }

    @Test
    fun aRefusedBearerIsRetriedOnceWithTheFreshToken() {
        val delegate = RecordingTransport()
        delegate.responses.add(HttpResponse(401, "invalid_token"))
        val served = HttpResponse(200, "{\"ok\":true}")
        delegate.responses.add(served)
        val seenByHook = mutableListOf<String>()
        val response = transport(delegate) { rejected ->
            seenByHook += rejected
            "ya29.fresh"
        }.post(
            URL,
            mapOf(
                "Authorization" to "Bearer ya29.stale",
                "X-Goog-AuthUser" to "0",
            ),
            "{\"videoId\":\"abc\"}",
        )
        assertSame(served, response)
        assertEquals(listOf("ya29.stale"), seenByHook)
        assertEquals(2, delegate.calls.size)
        // The retry carries the fresh token, every other header,
        // and the identical body.
        val retry = delegate.calls[1]
        assertEquals("Bearer ya29.fresh", retry.headers["Authorization"])
        assertEquals("0", retry.headers["X-Goog-AuthUser"])
        assertEquals("{\"videoId\":\"abc\"}", retry.body)
    }

    @Test
    fun aSecondRefusalIsReturnedAsIsAndNeverLoops() {
        val delegate = RecordingTransport()
        delegate.responses.add(HttpResponse(401, "invalid_token"))
        val secondRefusal = HttpResponse(401, "still invalid")
        delegate.responses.add(secondRefusal)
        val response = transport(delegate) { "ya29.fresh" }
            .post(URL, mapOf("Authorization" to "Bearer ya29.stale"), "{}")
        assertSame(secondRefusal, response)
        assertEquals(2, delegate.calls.size)
    }

    @Test
    fun noFreshTokenMeansTheOriginalRefusalStands() {
        val delegate = RecordingTransport()
        val refused = HttpResponse(401, "invalid_token")
        delegate.responses.add(refused)
        val response = transport(delegate) { null }
            .post(URL, mapOf("Authorization" to "Bearer ya29.stale"), "{}")
        assertSame(refused, response)
        assertEquals(1, delegate.calls.size)
    }

    @Test
    fun theSameTokenBackFromTheHookEndsTheAttempt() {
        val delegate = RecordingTransport()
        val refused = HttpResponse(401, "invalid_token")
        delegate.responses.add(refused)
        val response = transport(delegate) { rejected -> rejected }
            .post(URL, mapOf("Authorization" to "Bearer ya29.stale"), "{}")
        assertSame(refused, response)
        assertEquals(1, delegate.calls.size)
    }

    @Test
    fun aForbiddenAnswerIsNotARefreshProblem() {
        // 403 is an authorization refusal refresh cannot fix; the
        // decorator must not spend a refresh (or a retry) on it.
        val delegate = RecordingTransport()
        val forbidden = HttpResponse(403, "forbidden")
        delegate.responses.add(forbidden)
        var hookCalls = 0
        val response = transport(delegate) { hookCalls++; "ya29.fresh" }
            .post(URL, mapOf("Authorization" to "Bearer ya29.stale"), "{}")
        assertSame(forbidden, response)
        assertEquals(1, delegate.calls.size)
        assertEquals(0, hookCalls)
    }

    @Test
    fun aThrowingHookLeavesTheOriginalRefusalIntact() {
        val delegate = RecordingTransport()
        val refused = HttpResponse(401, "invalid_token")
        delegate.responses.add(refused)
        val response = transport(delegate) { throw RuntimeException("boom") }
            .post(URL, mapOf("Authorization" to "Bearer ya29.stale"), "{}")
        assertSame(refused, response)
        assertEquals(1, delegate.calls.size)
    }

    @Test
    fun getRequestsGetTheSameSecondChance() {
        val delegate = RecordingTransport()
        delegate.responses.add(HttpResponse(401, "invalid_token"))
        val served = HttpResponse(200, "{\"name\":\"Account\"}")
        delegate.responses.add(served)
        val response = transport(delegate) { "ya29.fresh" }
            .get(URL, mapOf("Authorization" to "Bearer ya29.stale"))
        assertSame(served, response)
        assertEquals(2, delegate.calls.size)
        assertEquals("GET", delegate.calls[1].method)
        assertEquals("Bearer ya29.fresh", delegate.calls[1].headers["Authorization"])
    }

    @Test
    fun formPostsAreNeverRetried() {
        // The OAuth endpoints speak form POSTs and map their own
        // protocol answers; a 401 there is data for that mapping,
        // never a Bearer-retry trigger.
        val delegate = RecordingTransport()
        val refused = HttpResponse(401, "unauthorized_client")
        delegate.responses.add(refused)
        var hookCalls = 0
        val response = transport(delegate) { hookCalls++; "ya29.fresh" }
            .postForm(URL, mapOf("Authorization" to "Bearer ya29.stale"), "a=b")
        assertSame(refused, response)
        assertEquals(1, delegate.calls.size)
        assertEquals(0, hookCalls)
    }

    private companion object {
        const val URL = "https://music.youtube.com/youtubei/v1/browse"
    }
}
