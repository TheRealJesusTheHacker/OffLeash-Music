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
    fun homeFeedOAuthReturnsTheRecordedFixturesExactSectionsAndItems() {
        // Round 45: the token road's home feed was pinned only
        // for sections[0]'s title, exactly like the cookie road
        // (see the fixture test's home pins) — the same in-place
        // last-section drop and middle-item reversal doctored
        // into homeFeedOAuth ran green across the full suite.
        // Pin the token road's exact recorded sections and items.
        val transport = FixedTransport(body = Fixtures.load("browse_home.json"))
        val feed = YoutubeiClient(transport).homeFeedOAuth(bearer)
        assertEquals(
            listOf("Quick picks", "Throwbacks"),
            feed.sections.map { it.title },
        )
        assertEquals(listOf(20, 10), feed.sections.map { it.items.size })
        val quickPicks = feed.sections[0].items
            .filterIsInstance<dev.cyberdogs.offleash.core.model.FeedItem.OfTrack>()
            .map { it.track.videoId }
        assertEquals(
            listOf(
                "xukbqwRuN5w", "Z85lxckrtzg", "CLSuAhT71yU", "mzhbRUniU4U",
                "ljAH6jNaApU", "b4mUzvt_lIw", "vOfyxPRunFM", "8WtxJPXX4e0",
                "tdS3ijuqxW4", "28jp-30w8Lg", "nEUZPehC6rs", "lFQdcPTTzSg",
                "D4Ha40XUE1E", "9KcnxA36s04", "Ai6LZlMCtNE", "FJX0JPXD2nM",
                "HJ-3MI_absQ", "QY495Kk9gKE", "UHV0ja0vhzU", "nyNm-dVBMoo",
            ),
            quickPicks,
        )
        val throwbacks = feed.sections[1].items
            .filterIsInstance<dev.cyberdogs.offleash.core.model.FeedItem.OfPlaylist>()
            .map { it.playlist.browseId }
        assertEquals(
            listOf(
                "VLRDCLAK5uy_lMzHW51iFg1Kx0d_2EHpzbOgCrwtu8cgI",
                "VLRDCLAK5uy_lvHI2Z7dSfpD5g8wvmePjWPfYwq5IgkLo",
                "VLRDCLAK5uy_lWy02cQBnTVTlwuRauaGKeUDH3L6PXNxI",
                "VLRDCLAK5uy_nZiG9ehz_MQoWQxY5yElsLHCcG0tv9PRg",
                "VLRDCLAK5uy_kN4eY_ibobGvCBwIJGEGpDjuwzYHIG_iE",
                "VLRDCLAK5uy_m_h-nx7OCFaq9AlyXv78lG0AuloqW_NUA",
                "VLRDCLAK5uy_kpxnNxJpPZjLKbL9WgvrPuErWkUxMP6x4",
                "VLRDCLAK5uy_m8tb7ahoXoyC82BkVgvWpJo2TUw8VnrlI",
                "VLRDCLAK5uy_lk7bC42AIcIBpSBiioTBrFdJeDNLSGCyE",
                "VLRDCLAK5uy_kfLKunrTVrL7hzgZz_skOKXn_MVzrpB6g",
            ),
            throwbacks,
        )
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
    fun libraryPlaylistsOAuthReturnsTheShelfPlaylistsInShelfOrder() {
        // Round 43: the OAuth twin of the cookie road's
        // library-order pin — its "InOrder" test likewise
        // asserted only count and non-empty fields, and
        // reversing the token road's collected list in
        // libraryPlaylistsOAuth itself ran green across the
        // full suite. Same recorded shelf, same exact order.
        val transport = FixedTransport(body = Fixtures.load("browse_home.json"))
        val playlists = YoutubeiClient(transport).libraryPlaylistsOAuth(bearer)
        assertEquals(
            listOf(
                "VLRDCLAK5uy_lMzHW51iFg1Kx0d_2EHpzbOgCrwtu8cgI",
                "VLRDCLAK5uy_lvHI2Z7dSfpD5g8wvmePjWPfYwq5IgkLo",
                "VLRDCLAK5uy_lWy02cQBnTVTlwuRauaGKeUDH3L6PXNxI",
                "VLRDCLAK5uy_nZiG9ehz_MQoWQxY5yElsLHCcG0tv9PRg",
                "VLRDCLAK5uy_kN4eY_ibobGvCBwIJGEGpDjuwzYHIG_iE",
                "VLRDCLAK5uy_m_h-nx7OCFaq9AlyXv78lG0AuloqW_NUA",
                "VLRDCLAK5uy_kpxnNxJpPZjLKbL9WgvrPuErWkUxMP6x4",
                "VLRDCLAK5uy_m8tb7ahoXoyC82BkVgvWpJo2TUw8VnrlI",
                "VLRDCLAK5uy_lk7bC42AIcIBpSBiioTBrFdJeDNLSGCyE",
                "VLRDCLAK5uy_kfLKunrTVrL7hzgZz_skOKXn_MVzrpB6g",
            ),
            playlists.map { it.browseId },
        )
        assertEquals("The Hits: '80s", playlists.first().title)
        assertEquals("Los Himnos", playlists.last().title)
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
    fun playlistOAuthPreservesTheRecordedTrackOrder() {
        // Round 42: the token road's playlist page was pinned
        // only for its title and non-emptiness, so a post-parse
        // transformation in playlistOAuth itself (the tracks
        // reversed) ran green across the full suite while every
        // playlist — and Liked Songs, which rides this call —
        // played backwards for token sessions. The shared parser
        // is order-pinned on the cookie road; the token road's
        // page must carry the same tracks in the same order.
        val transport = FixedTransport(body = Fixtures.load("playlist.json"))
        val page = YoutubeiClient(transport).playlistOAuth(bearer, "VLPLtest")
        assertEquals(52, page.tracks.size)
        assertEquals("dQw4w9WgXcQ", page.tracks.first().videoId)
        assertEquals("Never Gonna Give You Up", page.tracks.first().title)
        val cookiePage = YoutubeiClient(
            FixedTransport(body = Fixtures.load("playlist.json")),
        ).playlist("VLPLtest")
        assertEquals(
            cookiePage.tracks.map { it.videoId },
            page.tracks.map { it.videoId },
        )
        val liked = YoutubeiClient(
            FixedTransport(body = Fixtures.load("playlist.json")),
        ).likedSongsOAuth(bearer)
        assertEquals(
            page.tracks.map { it.videoId },
            liked.tracks.map { it.videoId },
        )
        assertBearerOnly(transport)
    }

    @Test
    fun playlistOAuthReturnsTheRecordedFixturesExactTrackOrder() {
        // Round 44: the pin above asserts the token road's full
        // order only against the COOKIE road's parse of the same
        // fixture — a cross-derived oracle. Both roads flow
        // through the shared pageTracks parser, so a post-parse
        // transformation inside that shared parser (the middle
        // tracks reversed, first and last preserved) moved both
        // sides together and ran green across the full suite
        // while every playlist and album played its middle
        // backwards. Pin the exact recorded order itself, from
        // the fixture, on the token road (and on Liked Songs,
        // which rides it).
        val expected = listOf(
            "dQw4w9WgXcQ", "ghGoI7xVtSI", "cyMHZVT91Dw", "mgRhrr_SHNw",
            "rJyKoMkB_aM", "DD70oKDlemE", "YwlVgpXXJS0", "4U-gFecl0BU",
            "dMbZfdrzILA", "wuggc60TVzU", "2MZMAxmmQwA", "IO9XlQrEt2Y",
            "v8y1OnE_MYo", "XVs3dsI5Hxc", "_buPIVDW2So", "AbjyINUWIXU",
            "AxYRHsQDdIw", "_W5JIr-AR1o", "IdkCEioCp24", "a9WHZ5M8I8w",
            "dJRsWJqDjFE", "q3yUYEkNUQU", "OiZlXOAOLLw", "ds8sVhxdvfA",
            "VbUuB1aN2DA", "ZemRrRr6fd0", "OdF8_SpBkgM", "rTga41r3a4s",
            "hLZW4U86cL4", "qKlmSZQJM6A", "xtpwcUSQEVM", "o-YBDTqX_ZU",
            "v_nRCCD0j-k", "8AMeRsZCJYs", "TE-DW-5pvbA", "QFK09y01me0",
            "0k7BvzQRrOI", "hEHLZ65v_dE", "GtL1huin9EE", "zdzcOS1ldfs",
            "nsCIeklgp1M", "oZL6cZoPuPM", "XGxIE1hr0w4", "vNS8edY8JV0",
            "a-mMiDiPdyM", "tuWyKF6WKKU", "IVLxoJ8vTn4", "wO-ue7E1EY0",
            "Hc9seZ0W8Ug", "yahsYfmEb8U", "w2Sz_yClQyI", "OIuG1bBkfs0",
        )
        val transport = FixedTransport(body = Fixtures.load("playlist.json"))
        val page = YoutubeiClient(transport).playlistOAuth(bearer, "VLPLtest")
        assertEquals(expected, page.tracks.map { it.videoId })
        val liked = YoutubeiClient(
            FixedTransport(body = Fixtures.load("playlist.json")),
        ).likedSongsOAuth(bearer)
        assertEquals(expected, liked.tracks.map { it.videoId })
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
