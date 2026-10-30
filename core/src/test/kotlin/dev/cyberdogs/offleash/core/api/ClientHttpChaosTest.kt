package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.FixedTransport
import dev.cyberdogs.offleash.core.GetTransport
import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.auth.IdentityLookup
import dev.cyberdogs.offleash.core.error.ContextRejected
import dev.cyberdogs.offleash.core.error.NetworkFailure
import dev.cyberdogs.offleash.core.error.OffleashException
import dev.cyberdogs.offleash.core.error.ParseFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Error-spam and wrong-shape statuses (bug sweep round 2, charter
 * 2): HTTP failures carrying youtubei-shaped error bodies, a 200
 * carrying an error payload on the PLAYER path (which bypasses the
 * browse path's parseJsonObject check), redirect-to-HTML (captive
 * portal) on the API, stream and LRCLIB paths, and an error body so
 * deeply nested that reading its message must not become the crash.
 */
class ClientHttpChaosTest {

    private val session = AuthSession(
        cookies = mapOf("SAPISID" to "sap", "SID" to "sid"),
    )

    private fun errorBody(message: String): String =
        """{"error": {"code": 400, "message": "$message", "status": "FAILED_PRECONDITION"}}"""

    private fun clientFor(body: String, status: Int = 200): YoutubeiClient =
        YoutubeiClient(FixedTransport(status = status, body = body))

    @Test
    fun clientErrorStatusesMapToContextRejectedWithServerMessage() {
        for (status in listOf(400, 401, 403, 404, 429)) {
            try {
                clientFor(errorBody("server says no MARKER$status"), status).home()
                fail("expected ContextRejected for HTTP $status")
            } catch (e: ContextRejected) {
                assertEquals("server says no MARKER$status", e.serverMessage)
            }
            // The player path maps by the same taxonomy (postRaw).
            try {
                clientFor(errorBody("server says no MARKER$status"), status).streamInfo("vid")
                fail("expected ContextRejected for player HTTP $status")
            } catch (e: ContextRejected) {
                assertEquals("server says no MARKER$status", e.serverMessage)
            }
        }
    }

    @Test
    fun serverErrorStatusesMapToNetworkFailureWithServerMessage() {
        for (status in listOf(500, 502, 503)) {
            try {
                clientFor(errorBody("backend exploded MARKER$status"), status).home()
                fail("expected NetworkFailure for HTTP $status")
            } catch (e: NetworkFailure) {
                assertTrue(e.message!!.contains("backend exploded MARKER$status"))
            }
            try {
                clientFor(errorBody("backend exploded MARKER$status"), status).streamInfo("vid")
                fail("expected NetworkFailure for player HTTP $status")
            } catch (e: NetworkFailure) {
                assertTrue(e.message!!.contains("backend exploded MARKER$status"))
            }
        }
    }

    @Test
    fun nonJsonErrorPageFallsBackToTheStatusNotTheBody() {
        val html = "<html><body>proxy error MARKERSECRET</body></html>"
        try {
            clientFor(html, 500).home()
            fail("expected NetworkFailure")
        } catch (e: NetworkFailure) {
            assertTrue(e.message!!.contains("HTTP 500"))
            assertFalse("error page content must not ride along", e.message!!.contains("MARKERSECRET"))
        }
        try {
            clientFor(html, 403).home()
            fail("expected ContextRejected")
        } catch (e: ContextRejected) {
            assertEquals("HTTP 403", e.serverMessage)
        }
    }

    @Test
    fun errorPayloadOn200ReachesThePlayerPathAsARefusal() {
        // Round 1 taught the BROWSE path this shape; the player path
        // parses raw (postRaw + StreamResolver) and used to degrade
        // it to PlayerRefused("unknown"), losing both the refusal's
        // name and the server's words.
        try {
            clientFor(errorBody("Precondition check failed.")).streamInfo("vid")
            fail("expected ContextRejected for a 200-with-error-payload player body")
        } catch (e: ContextRejected) {
            assertEquals("Precondition check failed.", e.serverMessage)
        }
    }

    @Test
    fun htmlOnThePlayerPathIsAParseFailureWithoutBodyEcho() {
        val html = "<html><body>captive portal MARKERSECRET</body></html>"
        try {
            clientFor(html).streamInfo("vid")
            fail("expected ParseFailure")
        } catch (e: ParseFailure) {
            assertFalse(e.message!!.contains("MARKERSECRET"))
        }
    }

    @Test
    fun htmlOnTheLrclibPathReadsAsNoSyncedLyrics() {
        val client = YoutubeiClient(
            GetTransport(body = "<html><body>portal MARKERSECRET</body></html>")
        )
        assertNull(client.syncedLyrics("Anything", "Anyone", 100))
    }

    @Test
    fun htmlOnTheIdentityPathsReadsAsFailedWithoutBodyEcho() {
        val html = "<html><body>portal MARKERSECRET</body></html>"
        val lookup = YoutubeiClient(GetTransport(body = html)).accountIdentities(session)
        assertTrue(lookup is IdentityLookup.Failed)
        assertFalse((lookup as IdentityLookup.Failed).detail!!.contains("MARKERSECRET"))

        val oauth = YoutubeiClient(GetTransport(body = html)).oauthIdentity("token_x")
        assertTrue(oauth is IdentityLookup.Failed)
        assertFalse((oauth as IdentityLookup.Failed).detail!!.contains("MARKERSECRET"))
    }

    @Test
    fun deeplyNestedErrorBodyStillMapsByStatus() {
        // extractErrorMessage parses the error body on the non-200
        // path; a nesting bomb there used to be able to overflow
        // the parser inside the error mapper itself. Run on a
        // small-stack thread to give the overflow its best chance.
        val sb = StringBuilder()
        repeat(5000) { sb.append("{\"a\":") }
        sb.append("{}")
        repeat(5000) { sb.append("}") }
        val body = sb.toString()
        var outcome: Throwable? = null
        val thread = Thread(
            null,
            {
                try {
                    clientFor(body, 500).home()
                } catch (t: Throwable) {
                    outcome = t
                }
            },
            "nested-error-body",
            256L * 1024L,
        )
        thread.start()
        thread.join(30_000)
        assertFalse("the call must have finished", thread.isAlive)
        assertTrue(
            "only the named NetworkFailure may escape, was $outcome",
            outcome is NetworkFailure,
        )
        assertTrue((outcome as NetworkFailure).message!!.contains("HTTP 500"))
    }

    @Test
    fun lrcTimestampOverflowIsSkippedNotWrappedToZero() {
        // The minute field is unbounded digits; the unchecked Long
        // math wrapped for absurd stamps and the coerceAtLeast(0)
        // then claimed the line was current from second zero.
        val body = """{"syncedLyrics":"[999999999999999999:00.00]junk line\n[00:01.00]good line"}"""
        val lines = YoutubeiClient(GetTransport(body = body))
            .syncedLyrics("Anything", "Anyone", 100)
        assertEquals(1, lines!!.size)
        assertEquals("good line", lines[0].text)
        assertEquals(1000L, lines[0].startMs)
        assertTrue(lines.all { (it.startMs ?: 0L) >= 0L })
    }

    @Test
    fun everyDocumentedCallSurvivesA500WithAnErrorBody() {
        val body = errorBody("backend exploded")
        val client = clientFor(body, 500)
        val calls: List<Pair<String, (YoutubeiClient) -> Any?>> = listOf(
            "home" to { it.home() },
            "search" to { it.search("x") },
            "album" to { it.album("MPREb_x") },
            "playlist" to { it.playlist("VL_x") },
            "related" to { it.related("vid") },
            "streamInfo" to { it.streamInfo("vid") },
        )
        for ((name, call) in calls) {
            try {
                call(client)
                fail("expected NetworkFailure from $name")
            } catch (e: OffleashException) {
                assertTrue("$name threw ${e.javaClass.simpleName}", e is NetworkFailure)
            }
        }
    }
}
