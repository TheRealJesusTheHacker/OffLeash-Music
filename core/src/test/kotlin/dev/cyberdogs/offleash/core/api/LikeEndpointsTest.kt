package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.FixedTransport
import dev.cyberdogs.offleash.core.Fixtures
import dev.cyberdogs.offleash.core.RoutingTransport
import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.error.ContextRejected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Account like read/write (Wave 2).
 *
 * The `derived_player_like_status.json` fixture is DERIVED-SYNTHETIC
 * (hand-built to the documented player shape; no real account data
 * exists). The absent-field case reuses the recorded player_ios.json,
 * which was verified by grep to carry no `likeStatus` field anywhere —
 * the player simply didn't report a state.
 */
class LikeEndpointsTest {

    private val session = AuthSession(
        cookies = mapOf("SAPISID" to "sap-value", "SID" to "sid-value"),
        visitorData = "CgVisitorDataValue",
    )

    private val videoId = "dQw4w9WgXcQ"

    /** A transport that always answers like calls with an empty body. */
    private fun likeClient() = RoutingTransport { _, _, _ ->
        HttpResponse(200, "{}")
    }

    private fun assertAuthHeaders(headers: Map<String, String>) {
        assertEquals(session.cookieHeader(), headers["Cookie"])
        val authorization = headers["Authorization"]
        assertTrue(
            "Authorization must be SAPISIDHASH <epochSeconds>_<sha1hex>, was $authorization",
            authorization != null && authorization.matches(Regex("""SAPISIDHASH \d+_[0-9a-f]{40}"""))
        )
    }

    @Test
    fun setLikeTruePostsToLikeLike() {
        val transport = likeClient()
        YoutubeiClient(transport).setLike(videoId, session, true)
        assertEquals(1, transport.requests.size)
        val request = transport.requests[0]
        assertEquals("POST", request.method)
        assertEquals(
            "https://music.youtube.com/youtubei/v1/like/like" +
                "?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30&prettyPrint=false",
            request.url
        )
        assertTrue(
            "target.videoId must ride in the body: ${request.body}",
            request.body!!.contains("\"videoId\":\"$videoId\"")
        )
        assertTrue("body must carry a target object: ${request.body}", request.body!!.contains("\"target\""))
        assertTrue(
            "the session visitor data must ride in the context: ${request.body}",
            request.body!!.contains("\"visitorData\":\"CgVisitorDataValue\"")
        )
        assertAuthHeaders(request.headers)
    }

    @Test
    fun setLikeFalsePostsToRemoveLike() {
        val transport = likeClient()
        YoutubeiClient(transport).setLike(videoId, session, false)
        assertEquals(1, transport.requests.size)
        assertTrue(
            "expected like/removelike, was ${transport.requests[0].url}",
            transport.requests[0].url.contains("/v1/like/removelike?")
        )
    }

    @Test
    fun setLikeMaps4xxToNamedError() {
        // Success is the call returning; a refusal maps through the
        // shared postJson error mapping to the named errors.
        val transport = FixedTransport(
            status = 400,
            body = """{"error":{"code":400,"message":"Precondition check failed."}}"""
        )
        try {
            YoutubeiClient(transport).setLike(videoId, session, true)
            fail("expected ContextRejected")
        } catch (e: ContextRejected) {
            assertTrue(e.message!!.contains("Precondition check failed."))
        }
    }

    @Test
    fun likeStatusParsesLikedFromTopLevelField() {
        val transport = FixedTransport(body = Fixtures.load("derived_player_like_status.json"))
        val status = YoutubeiClient(transport).likeStatus("derivedVideoId", session)
        assertEquals(LikeStatus.LIKED, status)
        assertTrue("expected a player call, was ${transport.lastUrl}", transport.lastUrl!!.contains("/v1/player?"))
        assertTrue("player call must stay on music host, was ${transport.lastUrl}", transport.lastUrl!!.startsWith("https://music.youtube.com/"))
        assertTrue(transport.lastBody!!.contains("\"videoId\":\"derivedVideoId\""))
        assertAuthHeaders(transport.lastHeaders!!)
    }

    @Test
    fun likeStatusParsesDislikedFromVideoDetails() {
        // Defensive second location: the field nested under
        // videoDetails reads the same.
        val body = """{"playabilityStatus":{"status":"OK"},""" +
            """"videoDetails":{"videoId":"x","likeStatus":"DISLIKE"}}"""
        assertEquals(
            LikeStatus.DISLIKED,
            YoutubeiClient(FixedTransport(body = body)).likeStatus("x", session)
        )
    }

    @Test
    fun likeStatusParsesIndifferent() {
        val body = """{"playabilityStatus":{"status":"OK"},"likeStatus":"INDIFFERENT"}"""
        assertEquals(
            LikeStatus.INDIFFERENT,
            YoutubeiClient(FixedTransport(body = body)).likeStatus("x", session)
        )
    }

    @Test
    fun likeStatusIsNullWhenFieldAbsent() {
        // No likeStatus anywhere in the recorded response: unknown is
        // an honest state, not a guess.
        assertNull(
            YoutubeiClient(FixedTransport(body = Fixtures.load("player_ios.json")))
                .likeStatus("dQw4w9WgXcQ", session)
        )
    }

    @Test
    fun likeStatusIsNullForUnrecognizedValue() {
        val body = """{"playabilityStatus":{"status":"OK"},"likeStatus":"SOME_FUTURE_VALUE"}"""
        assertNull(
            YoutubeiClient(FixedTransport(body = body)).likeStatus("x", session)
        )
    }
}
