package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.FixedTransport
import dev.cyberdogs.offleash.core.Fixtures
import dev.cyberdogs.offleash.core.RoutingTransport
import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.model.MoodCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pagination honesty (bug sweep round 1). Before this sweep no
 * continuation token survived parsing: the recorded playlist
 * fixture offers one, and every list longer than its first page —
 * playlists, Liked Songs, search — silently truncated while
 * presenting as complete. These tests pin the token's path end to
 * end: fixture page → offered token → continuation request body →
 * parsed next chunk → the token the chunk in turn offers.
 */
class ContinuationTest {

    private fun trackRow(videoId: String, title: String): String =
        """{"musicResponsiveListItemRenderer": {
            "flexColumns": [
                {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "$title"}]}}}
            ],
            "playlistItemData": {"videoId": "$videoId"}
        }}"""

    private fun playlistContinuationBody(token: String?): String {
        val continuations = if (token == null) {
            ""
        } else {
            """, "continuations": [{"nextContinuationData": {"continuation": "$token"}}]"""
        }
        return """{"continuationContents": {"musicPlaylistShelfContinuation": {
            "contents": [${trackRow("vidA", "Song A")}, ${trackRow("vidB", "Song B")}]
            $continuations
        }}}"""
    }

    @Test
    fun playlistFixtureOffersItsContinuationToken() {
        val page = YoutubeiClient(FixedTransport(body = Fixtures.load("playlist.json")))
            .playlist("VLPLd5Vfgy_sILZXNGHKlAHnwPOEhboVQNBG")
        assertEquals(52, page.tracks.size)
        assertNotNull("the fixture's section list offers a continuation", page.continuation)
        assertTrue(page.continuation!!.startsWith("4qmFsgI0EiRWTFBMZDVWZmd5X3NJTFpY"))
    }

    @Test
    fun searchFixtureOffersNoContinuationAndThatIsTheTrueEnd() {
        val results = YoutubeiClient(FixedTransport(body = Fixtures.load("search.json")))
            .search("never gonna give you up")
        assertTrue(results.sections.isNotEmpty())
        assertNull(results.continuation)
    }

    @Test
    fun playlistContinuationSendsTheTokenAndParsesTheChunk() {
        var sentBody: String? = null
        val transport = RoutingTransport { _, _, body ->
            sentBody = body
            HttpResponse(200, playlistContinuationBody("TOK2"))
        }
        val chunk = YoutubeiClient(transport).playlistContinuation("TOK1")
        assertTrue(
            "the request must carry the offered token",
            sentBody!!.contains("\"continuation\":\"TOK1\""),
        )
        assertEquals(listOf("vidA", "vidB"), chunk.tracks.map { it.videoId })
        assertEquals("TOK2", chunk.continuation)
    }

    @Test
    fun playlistContinuationWithoutAnOfferedTokenIsTheEnd() {
        val transport = RoutingTransport { _, _, _ ->
            HttpResponse(200, playlistContinuationBody(null))
        }
        val chunk = YoutubeiClient(transport).playlistContinuation("TOK9")
        assertEquals(2, chunk.tracks.size)
        assertNull(chunk.continuation)
    }

    @Test
    fun repeatedTokenComesBackAsOfferedAndTheTrackerTerminatesIt() {
        // The parser reports honestly what the server offered — even
        // a repeat of the token just sent. Ending the loop is the
        // tracker's job, pinned in ContinuationTrackerTest.
        val transport = RoutingTransport { _, _, _ ->
            HttpResponse(200, playlistContinuationBody("TOK1"))
        }
        val chunk = YoutubeiClient(transport).playlistContinuation("TOK1")
        assertEquals("TOK1", chunk.continuation)
    }

    @Test
    fun searchContinuationParsesSectionListContinuation() {
        val body = """{"continuationContents": {"sectionListContinuation": {
            "contents": [
                {"musicShelfRenderer": {"contents": [${trackRow("vidC", "Song C")}]}}
            ],
            "continuations": [{"nextContinuationData": {"continuation": "STOK2"}}]
        }}}"""
        var sentBody: String? = null
        val transport = RoutingTransport { _, _, bodySent ->
            sentBody = bodySent
            HttpResponse(200, body)
        }
        val results = YoutubeiClient(transport).searchContinuation("STOK1")
        assertTrue(sentBody!!.contains("\"continuation\":\"STOK1\""))
        val tracks = results.sections.flatMap { it.items }
        assertEquals(1, tracks.size)
        assertEquals("STOK2", results.continuation)
    }

    @Test
    fun oauthPlaylistContinuationPreservesTheChunkOrder() {
        // Round 43: the token road's continuation chunk was
        // pinned only for its size and end token, so reversing
        // the chunk in playlistContinuationOAuth itself ran
        // green across the full suite while every token-road
        // Load-more appended its tracks backwards. The cookie
        // road's chunk order is pinned above; the token road
        // must carry the same chunk in the same order.
        val transport = RoutingTransport { _, _, _ ->
            HttpResponse(200, playlistContinuationBody("TOK2"))
        }
        val chunk = YoutubeiClient(transport)
            .playlistContinuationOAuth("token-abc", "TOK1")
        assertEquals(listOf("vidA", "vidB"), chunk.tracks.map { it.videoId })
        assertEquals(listOf("Song A", "Song B"), chunk.tracks.map { it.title })
        assertEquals("TOK2", chunk.continuation)
    }

    @Test
    fun searchContinuationChunkTrackKeepsItsIdentity() {
        // Round 43: the search-continuation test above pins
        // the chunk's SIZE (1) and the offered token — the
        // size assertion catches a duplicated or dropped item
        // (proven: duplicating the chunk fails it), but the
        // chunk track's identity stood asserted nowhere, so
        // rewriting the track's video id in
        // searchContinuation itself ran green across the full
        // suite while the appended row played a different
        // track than it displayed. Pin the identity.
        val body = """{"continuationContents": {"sectionListContinuation": {
            "contents": [
                {"musicShelfRenderer": {"contents": [${trackRow("vidC", "Song C")}]}}
            ],
            "continuations": [{"nextContinuationData": {"continuation": "STOK2"}}]
        }}}"""
        val transport = RoutingTransport { _, _, _ -> HttpResponse(200, body) }
        val results = YoutubeiClient(transport).searchContinuation("STOK1")
        val tracks = results.sections.flatMap { it.items }
            .filterIsInstance<dev.cyberdogs.offleash.core.model.FeedItem.OfTrack>()
            .map { it.track }
        assertEquals(1, tracks.size)
        assertEquals("vidC", tracks[0].videoId)
        assertEquals("Song C", tracks[0].title)
        assertEquals("STOK2", results.continuation)
    }

    @Test
    fun oauthPlaylistContinuationSendsTheOfferedTokenNotTheBearer() {
        // Round 47: the token road's continuation request body
        // was asserted nowhere -- the OAuth tests checked the
        // Authorization header and the parsed chunk, so keying
        // the continuation field on the BEARER token instead of
        // the offered continuation token ran green across the
        // full suite while every token-road Load more (Liked
        // Songs included) paged with the wrong identity. The
        // cookie road's body is pinned above; pin this road's.
        var sentBody: String? = null
        val transport = RoutingTransport { _, _, body ->
            sentBody = body
            HttpResponse(200, playlistContinuationBody("TOK2"))
        }
        val chunk = YoutubeiClient(transport)
            .playlistContinuationOAuth("token-abc", "TOK1")
        assertTrue(
            "the request must carry the offered continuation token",
            sentBody!!.contains("\"continuation\":\"TOK1\""),
        )
        assertTrue(
            "the bearer token is the Authorization header's, never the continuation",
            !sentBody!!.contains("\"continuation\":\"token-abc\""),
        )
        assertEquals("TOK2", chunk.continuation)
    }

    @Test
    fun likedSongsCarriesTheFixturesContinuationOnBothRoads() {
        // Round 48: playlist()/playlistOAuth() carry the
        // fixture's continuation under their own pins, and the
        // Liked Songs wrappers were asserted for title, browse
        // id, and (token road) track order — never for the
        // token. Dropping it at either wrapper
        // (`.copy(continuation = null)`) ran green across the
        // full core suite while Liked Songs — the one playlist
        // whose paging matters most — could never load past its
        // first page on that road. Pin the token on both.
        val session = AuthSession(
            cookies = mapOf("SAPISID" to "sap-value"),
            authUser = 0,
        )
        val cookie = YoutubeiClient(FixedTransport(body = Fixtures.load("playlist.json")))
            .likedSongs(session)
        assertEquals(52, cookie.tracks.size)
        assertNotNull("the cookie road's Liked Songs must offer its continuation", cookie.continuation)
        assertTrue(cookie.continuation!!.startsWith("4qmFsgI0EiRWTFBMZDVWZmd5X3NJTFpY"))
        val oauth = YoutubeiClient(FixedTransport(body = Fixtures.load("playlist.json")))
            .likedSongsOAuth("token-abc")
        assertEquals(52, oauth.tracks.size)
        assertNotNull("the token road's Liked Songs must offer its continuation", oauth.continuation)
        assertTrue(oauth.continuation!!.startsWith("4qmFsgI0EiRWTFBMZDVWZmd5X3NJTFpY"))
    }

    @Test
    fun chipLessSearchResponsesFallBackToThePinnedFilterSetOnBothRoads() {
        // Round 48: search parses its filter chips from the
        // response and falls back to the pinned set when the
        // response publishes none — the code says so, and the
        // UI's filter row depends on it. No test served either
        // road a chip-less response, so disabling the fallback
        // in search() and in searchContinuation() each ran
        // green across the full core suite (the row collapsed
        // to the lone "All" chip). Serve both roads the
        // chip-less synthetic chunk and pin the fallback.
        val body = """{"continuationContents": {"sectionListContinuation": {
            "contents": [
                {"musicShelfRenderer": {"contents": [${trackRow("vidC", "Song C")}]}}
            ],
            "continuations": [{"nextContinuationData": {"continuation": "STOK2"}}]
        }}}"""
        val transport = RoutingTransport { _, _, _ -> HttpResponse(200, body) }
        val expected = SearchFilters.ALL_FILTERS.map { it.label to it.params }
        val firstPage = YoutubeiClient(transport).search("anything")
        assertEquals(
            expected,
            firstPage.filters.map { it.label to it.params },
        )
        val chunk = YoutubeiClient(transport).searchContinuation("STOK1")
        assertEquals(
            expected,
            chunk.filters.map { it.label to it.params },
        )
    }

    @Test
    fun moodPlaylistsParseAContinuationShapedCategoryResponse() {
        // Round 48: moodPlaylists was pinned only via the home
        // fixture's titled shelves. A category page's
        // continuation arrives as a bare shelf continuation —
        // ONE untitled section of rows — and dropping untitled
        // sections at the client ran green across the full
        // core suite while that whole chunk rendered as
        // nothing. Pin the shape (synthetic body, the
        // established style of this class: no recorded category
        // fixture exists in the corpus).
        val body = """{"continuationContents": {"musicShelfContinuation": {
            "contents": [${trackRow("moreVid1", "More Song 1")}, ${trackRow("moreVid2", "More Song 2")}]
        }}}"""
        val transport = RoutingTransport { _, _, _ -> HttpResponse(200, body) }
        val sections = YoutubeiClient(transport).moodPlaylists(
            MoodCategory(title = "Chill", params = "ggMPChillParams"),
        )
        assertEquals(1, sections.size)
        assertNull(sections[0].title)
        val tracks = sections[0].items
            .filterIsInstance<dev.cyberdogs.offleash.core.model.FeedItem.OfTrack>()
            .map { it.track }
        assertEquals(listOf("moreVid1", "moreVid2"), tracks.map { it.videoId })
        assertEquals(listOf("More Song 1", "More Song 2"), tracks.map { it.title })
    }

    @Test
    fun artistParsesAContinuationShapedResponse() {
        // Round 48: the artist fixture carries exactly one
        // titled section, so the continuation shape of an
        // artist response — a bare shelf continuation, one
        // untitled section — was asserted nowhere, and
        // dropping untitled sections at the client ran green
        // across the full core suite. Pin the shape.
        val body = """{"continuationContents": {"musicShelfContinuation": {
            "contents": [${trackRow("artVid1", "Artist Song 1")}, ${trackRow("artVid2", "Artist Song 2")}]
        }}}"""
        val transport = RoutingTransport { _, _, _ -> HttpResponse(200, body) }
        val page = YoutubeiClient(transport).artist("UCsomeArtist")
        assertEquals(1, page.sections.size)
        assertNull(page.sections[0].title)
        val tracks = page.sections[0].items
            .filterIsInstance<dev.cyberdogs.offleash.core.model.FeedItem.OfTrack>()
            .map { it.track }
        assertEquals(listOf("artVid1", "artVid2"), tracks.map { it.videoId })
        assertEquals(listOf("Artist Song 1", "Artist Song 2"), tracks.map { it.title })
    }

    @Test
    fun oauthPlaylistContinuationUsesTheTokenRoad() {
        var authHeader: String? = null
        val transport = RoutingTransport { _, headers, _ ->
            authHeader = headers["Authorization"]
            HttpResponse(200, playlistContinuationBody(null))
        }
        val chunk = YoutubeiClient(transport)
            .playlistContinuationOAuth("token-abc", "TOK1")
        assertEquals("Bearer token-abc", authHeader)
        assertEquals(2, chunk.tracks.size)
        assertNull(chunk.continuation)
    }
}
