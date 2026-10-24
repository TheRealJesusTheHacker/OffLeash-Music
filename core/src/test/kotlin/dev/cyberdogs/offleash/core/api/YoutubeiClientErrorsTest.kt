package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.FixedTransport
import dev.cyberdogs.offleash.core.Fixtures
import dev.cyberdogs.offleash.core.ThrowingTransport
import dev.cyberdogs.offleash.core.error.ContextRejected
import dev.cyberdogs.offleash.core.error.NetworkFailure
import dev.cyberdogs.offleash.core.error.ParseFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** HTTP/transport failure mapping (SPEC §6) and context wire shapes. */
class YoutubeiClientErrorsTest {

    @Test
    fun precondition400MapsToContextRejected() {
        val body = """{"error":{"code":400,"message":"Precondition check failed.","status":"FAILED_PRECONDITION"}}"""
        val client = YoutubeiClient(FixedTransport(status = 400, body = body))
        try {
            client.streamInfo("dQw4w9WgXcQ")
            fail("expected ContextRejected")
        } catch (e: ContextRejected) {
            assertEquals("Precondition check failed.", e.serverMessage)
            assertTrue(e.message!!.contains("Precondition"))
        }
    }

    @Test
    fun badRequestOnBrowseMapsToContextRejected() {
        val body = """{"error":{"code":400,"message":"Precondition check failed."}}"""
        val client = YoutubeiClient(FixedTransport(status = 400, body = body))
        try {
            client.home()
            fail("expected ContextRejected")
        } catch (e: ContextRejected) {
            assertTrue(e.message!!.contains("Precondition"))
        }
    }

    @Test
    fun serverErrorMapsToNetworkFailure() {
        val body = """{"error":{"code":503,"message":"Service unavailable"}}"""
        val client = YoutubeiClient(FixedTransport(status = 503, body = body))
        try {
            client.home()
            fail("expected NetworkFailure")
        } catch (e: NetworkFailure) {
            assertTrue(e.message!!.contains("Couldn't reach YouTube Music"))
        }
    }

    @Test
    fun throwingTransportMapsToNetworkFailure() {
        val client = YoutubeiClient(ThrowingTransport())
        try {
            client.search("anything")
            fail("expected NetworkFailure")
        } catch (e: NetworkFailure) {
            assertTrue(e.message!!.contains("connection reset by peer"))
        }
    }

    @Test
    fun nonJsonBodyMapsToParseFailure() {
        val client = YoutubeiClient(FixedTransport(body = "<html>not json</html>"))
        try {
            client.home()
            fail("expected ParseFailure")
        } catch (e: ParseFailure) {
            assertTrue(e.message!!.contains("doesn't understand"))
        }
    }

    @Test
    fun streamInfoFromPlayerFixtureIsOk() {
        val info = YoutubeiClient(FixedTransport(body = Fixtures.load("player_ios.json")))
            .streamInfo("dQw4w9WgXcQ")
        assertTrue(info.url.startsWith("https://"))
        assertTrue(info.mimeType.startsWith("audio/"))
        assertTrue(info.bitrate > 0)
    }

    @Test
    fun browseRequestsUseWebRemixContext() {
        val transport = FixedTransport(body = Fixtures.load("browse_home.json"))
        YoutubeiClient(transport).home()
        assertTrue(transport.lastUrl!!.startsWith("https://music.youtube.com/youtubei/v1/browse"))
        assertTrue(transport.lastBody!!.contains("\"clientName\":\"WEB_REMIX\""))
        assertTrue(transport.lastBody!!.contains("1.20241127.01.00"))
        assertTrue(transport.lastBody!!.contains("FEmusic_home"))
    }

    @Test
    fun playerRequestsUseIosContext() {
        val transport = FixedTransport(body = Fixtures.load("player_ios.json"))
        YoutubeiClient(transport).streamInfo("dQw4w9WgXcQ")
        assertTrue(transport.lastUrl!!.startsWith("https://www.youtube.com/youtubei/v1/player"))
        assertTrue(transport.lastBody!!.contains("\"clientName\":\"IOS\""))
        assertTrue(transport.lastBody!!.contains("20.10.4"))
        assertTrue(transport.lastBody!!.contains("iPhone16,2"))
        assertEquals(
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X)",
            transport.lastHeaders!!["User-Agent"]
        )
    }
}
