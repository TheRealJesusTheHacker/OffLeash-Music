package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.FixedTransport
import dev.cyberdogs.offleash.core.Fixtures
import dev.cyberdogs.offleash.core.GetTransport
import dev.cyberdogs.offleash.core.auth.IdentityLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The token road's surfaces (0.7.0): every OAuth call must ride the
 * ANDROID_MUSIC context with an `Authorization: Bearer` header —
 * and NO cookie material of any kind — and the recorded response
 * fixtures must parse through the shared parsers exactly as they
 * do on the cookie road (the response shapes are the surface's own;
 * only the auth mechanism differs). The userinfo body is
 * DERIVED-SYNTHETIC, hand-built to Google's documented shape.
 */
class OAuthSurfacesTest {

    private val bearer = "ya29.test-token"

    private fun assertBearerOnly(transport: FixedTransport) {
        val headers = transport.lastHeaders!!
        assertEquals("Bearer $bearer", headers["Authorization"])
        assertFalse(
            "the token road must never send cookies",
            headers.containsKey("Cookie"),
        )
        assertFalse(
            "the token road must never send a SAPISIDHASH",
            headers.values.any { it.contains("SAPISIDHASH") },
        )
        assertTrue(
            "expected the ANDROID_MUSIC context, body was ${transport.lastBody}",
            transport.lastBody!!.contains("\"clientName\":\"ANDROID_MUSIC\""),
        )
        assertFalse(
            "the token road injects no visitor data",
            transport.lastBody!!.contains("visitorData"),
        )
    }

    @Test
    fun homeFeedOAuthParsesTheRecordedHomeFixture() {
        val transport = FixedTransport(body = Fixtures.load("browse_home.json"))
        val feed = YoutubeiClient(transport).homeFeedOAuth(bearer)
        assertTrue(feed.sections.isNotEmpty())
        assertEquals("Quick picks", feed.sections[0].title)
        assertTrue(transport.lastBody!!.contains("FEmusic_home"))
        assertBearerOnly(transport)
    }

    @Test
    fun libraryPlaylistsOAuthCollectsPlaylistItemsInOrder() {
        // Same recorded-shelf trick as the cookie road's test: the
        // home fixture's "Throwbacks" shelf is ten playlist cards.
        val transport = FixedTransport(body = Fixtures.load("browse_home.json"))
        val playlists = YoutubeiClient(transport).libraryPlaylistsOAuth(bearer)
        assertTrue(transport.lastBody!!.contains("FEmusic_liked_playlists"))
        assertEquals(10, playlists.size)
        assertTrue(playlists.all { it.browseId.isNotEmpty() && it.title.isNotEmpty() })
        assertBearerOnly(transport)
    }

    @Test
    fun likedSongsOAuthBrowsesVlLm() {
        val transport = FixedTransport(body = Fixtures.load("playlist.json"))
        val page = YoutubeiClient(transport).likedSongsOAuth(bearer)
        assertEquals("Never Gonna Give You Up", page.title)
        assertTrue(transport.lastBody!!.contains("\"browseId\":\"VLLM\""))
        assertBearerOnly(transport)
    }

    @Test
    fun playlistOAuthParsesThePlaylistFixture() {
        val transport = FixedTransport(body = Fixtures.load("playlist.json"))
        val page = YoutubeiClient(transport).playlistOAuth(bearer, "VLPLtest")
        assertTrue(page.tracks.isNotEmpty())
        assertTrue(transport.lastBody!!.contains("\"browseId\":\"VLPLtest\""))
        assertBearerOnly(transport)
    }

    @Test
    fun streamInfoOAuthResolvesTheAndroidPlayerFixture() {
        val transport = FixedTransport(body = Fixtures.load("derived_player_android.json"))
        val info = YoutubeiClient(transport).streamInfoOAuth("derivedVideoId", bearer)
        assertTrue(info.url.isNotBlank())
        assertTrue(transport.lastUrl!!.contains("/v1/player?"))
        assertTrue(transport.lastBody!!.contains("\"videoId\":\"derivedVideoId\""))
        assertBearerOnly(transport)
    }

    @Test
    fun likeStatusOAuthReadsTheLikeStatusField() {
        val transport = FixedTransport(body = Fixtures.load("derived_player_like_status.json"))
        val status = YoutubeiClient(transport).likeStatusOAuth("derivedVideoId", bearer)
        assertEquals(LikeStatus.LIKED, status)
        assertBearerOnly(transport)
    }

    @Test
    fun setLikeOAuthPostsTheLikeEndpoints() {
        val transport = FixedTransport(body = "{}")
        YoutubeiClient(transport).setLikeOAuth("derivedVideoId", bearer, liked = true)
        assertTrue(transport.lastUrl!!.contains("/v1/like/like?"))
        assertTrue(transport.lastBody!!.contains("\"videoId\":\"derivedVideoId\""))
        assertBearerOnly(transport)

        val unlikeTransport = FixedTransport(body = "{}")
        YoutubeiClient(unlikeTransport).setLikeOAuth("derivedVideoId", bearer, liked = false)
        assertTrue(unlikeTransport.lastUrl!!.contains("/v1/like/removelike?"))
    }

    @Test
    fun oauthIdentityParsesUserinfo() {
        val body = """
            {"sub":"1234567890","name":"Test Listener",
             "email":"listener@example.com",
             "picture":"https://lh3.example.com/avatar.jpg",
             "email_verified":true}
        """.trimIndent()
        val transport = GetTransport(body = body)
        val lookup = YoutubeiClient(transport).oauthIdentity(bearer)
        val found = lookup as IdentityLookup.Found
        assertEquals(1, found.identities.size)
        val identity = found.identities[0]
        assertEquals("Test Listener", identity.name)
        assertEquals("listener@example.com", identity.email)
        assertEquals("https://lh3.example.com/avatar.jpg", identity.avatarUrl)
        assertTrue(identity.isSelected)
        assertEquals(
            "Bearer $bearer",
            transport.lastGetHeaders!!["Authorization"],
        )
        assertTrue(transport.lastGetUrl!!.startsWith("https://openidconnect.googleapis.com/"))
    }

    @Test
    fun oauthIdentityMapsRefusalsAndEmpties() {
        val rejected = YoutubeiClient(GetTransport(status = 401, body = "{}"))
            .oauthIdentity(bearer)
        assertTrue(rejected is IdentityLookup.Rejected)

        val empty = YoutubeiClient(GetTransport(body = "{}")).oauthIdentity(bearer)
        val found = empty as IdentityLookup.Found
        assertTrue(found.identities.isEmpty())

        val garbage = YoutubeiClient(GetTransport(body = "not json")).oauthIdentity(bearer)
        assertTrue(garbage is IdentityLookup.Failed)

        val dead = YoutubeiClient(
            GetTransport(failure = RuntimeException("offline"))
        ).oauthIdentity(bearer)
        assertTrue(dead is IdentityLookup.Failed)
    }
}
