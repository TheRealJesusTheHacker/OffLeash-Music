package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.FixedTransport
import dev.cyberdogs.offleash.core.Fixtures
import dev.cyberdogs.offleash.core.RoutingTransport
import dev.cyberdogs.offleash.core.error.ContextRejected
import dev.cyberdogs.offleash.core.error.ParseFailure
import dev.cyberdogs.offleash.core.model.LyricsResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Client-level payload armor (bug sweep round 1): what the transport
 * hands back is untrusted. An HTML body (captive portal / error
 * page), a truncated body, and a deeply nested body are named
 * [ParseFailure]s; a 200 carrying a top-level error payload is the
 * refusal it actually is ([ContextRejected]) — before this sweep it
 * parsed as an empty feed, a fake "no content" for a real error.
 * No body content may leak into an exception message.
 */
class ClientPayloadArmorTest {

    private fun clientFor(body: String, status: Int = 200): YoutubeiClient =
        YoutubeiClient(FixedTransport(status = status, body = body))

    @Test
    fun htmlBodyIsAParseFailureNotAnEmptyFeed() {
        val html = "<html><head><title>Sign in</title></head>" +
            "<body>captive portal MARKERSECRET</body></html>"
        for (call in listOf<(YoutubeiClient) -> Any>(
            { it.home() },
            { it.search("anything") },
            { it.charts() },
            { it.moods() },
            { it.album("MPREb_test") },
            { it.artist("UC_test") },
            { it.playlist("VL_test") },
            { it.related("abc123") },
        )) {
            try {
                call(clientFor(html))
                fail("expected ParseFailure for HTML body")
            } catch (e: ParseFailure) {
                assertFalse(
                    "body content must never enter diagnostics",
                    e.message!!.contains("MARKERSECRET"),
                )
            }
        }
    }

    @Test
    fun truncatedFixtureBodyIsAParseFailure() {
        val full = Fixtures.load("browse_home.json")
        val truncated = full.substring(0, full.length / 2)
        try {
            clientFor(truncated).home()
            fail("expected ParseFailure for truncated body")
        } catch (e: ParseFailure) {
            assertTrue(e.message!!.contains("browse"))
        }
    }

    @Test
    fun errorPayloadOn200IsARefusalNotAFakeEmpty() {
        val body = """{"error": {"code": 400, "message": "Precondition check failed."}}"""
        try {
            clientFor(body).home()
            fail("expected ContextRejected for a 200-with-error-payload")
        } catch (e: ContextRejected) {
            assertEquals("Precondition check failed.", e.serverMessage)
        }
        try {
            clientFor(body).search("anything")
            fail("expected ContextRejected for a 200-with-error-payload")
        } catch (e: ContextRejected) {
            assertEquals("Precondition check failed.", e.serverMessage)
        }
    }

    @Test
    fun deeplyNestedBodyNeverEscapesAsACrash() {
        // ~5000 levels of nesting. Whether the JSON parser's own
        // recursion overflows on this shape is genuinely
        // environment-dependent (stack size is a hint, JIT state
        // changes frame sizes — the same body has been observed to
        // parse in one JVM and overflow in another), so this test
        // pins the invariant that holds in EVERY environment: the
        // call either completes with its honest (empty) result, or
        // fails as the named ParseFailure — a StackOverflowError or
        // any other Error must never escape the client boundary,
        // because parseJsonObject maps Throwable, not just
        // Exception. Run on a small-stack thread to give the
        // overflow its best chance to happen.
        val sb = StringBuilder()
        repeat(5000) { sb.append("{\"a\":") }
        sb.append("{}")
        repeat(5000) { sb.append("}") }
        val body = sb.toString()
        var outcome: Throwable? = null
        var completed = false
        val thread = Thread(
            null,
            {
                try {
                    clientFor(body).home()
                    completed = true
                } catch (t: Throwable) {
                    outcome = t
                }
            },
            "deep-body-parse",
            256L * 1024L,
        )
        thread.start()
        thread.join(30_000)
        assertFalse("the parse thread must have finished", thread.isAlive)
        if (!completed) {
            assertTrue(
                "only ParseFailure may escape the boundary, was $outcome",
                outcome is ParseFailure,
            )
            assertFalse((outcome as ParseFailure).message!!.contains("{\"a\""))
        }
    }

    @Test
    fun nonObjectJsonBodiesAreParseFailures() {
        for (body in listOf("[1,2,3]", "\"text\"", "42")) {
            try {
                clientFor(body).home()
                fail("expected ParseFailure for body $body")
            } catch (e: ParseFailure) {
                Unit
            }
        }
    }

    @Test
    fun lyricsDegradesToUnavailableOnHostileShapes() {
        // Lyrics' contract is "never an exception": a next response
        // whose Lyrics tab is junk, and a browse response that is an
        // HTML page, both read as Unavailable.
        val junkTab = """{"contents": {"tabRenderer": {"title": "Lyrics",
            "endpoint": "junk"}}}"""
        val transport = RoutingTransport { _, _, _ ->
            HttpResponse(200, junkTab)
        }
        val result = YoutubeiClient(transport).lyrics("abc123")
        assertTrue(result is LyricsResult.Unavailable)

        val htmlTransport = RoutingTransport { _, _, _ ->
            HttpResponse(200, "<html>portal</html>")
        }
        val htmlResult = YoutubeiClient(htmlTransport).lyrics("abc123")
        assertTrue(htmlResult is LyricsResult.Unavailable)
    }
}
