package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.FixedTransport
import dev.cyberdogs.offleash.core.Fixtures
import dev.cyberdogs.offleash.core.GetTransport
import dev.cyberdogs.offleash.core.ThrowingTransport
import dev.cyberdogs.offleash.core.auth.AuthSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Authed browse smoke (Phase 2): with a session, requests carry the
 * Cookie + Authorization headers and the session visitor data, and the
 * recorded Phase 1 fixtures parse exactly as they do anonymously.
 */
class AuthedBrowseTest {

    private val session = AuthSession(
        cookies = mapOf("SAPISID" to "sap-value", "SID" to "sid-value"),
        authUser = 1,
        visitorData = "CgVisitorDataValue",
    )

    @Test
    fun authedHomeSendsAuthHeadersAndParsesFixtureUnchanged() {
        val transport = FixedTransport(body = Fixtures.load("browse_home.json"))
        val sections = YoutubeiClient(transport).home(session)
        assertEquals("Quick picks", sections[0].title)

        val headers = transport.lastHeaders!!
        assertEquals(session.cookieHeader(), headers["Cookie"])
        val authorization = headers["Authorization"]
        assertTrue(
            "Authorization must be <epochSeconds>_<sha1hex>, was $authorization",
            authorization != null && authorization.matches(Regex("""\d+_[0-9a-f]{40}"""))
        )
        assertEquals("1", headers["X-Goog-Authuser"])
        assertEquals("CgVisitorDataValue", headers["X-Goog-Visitor-Id"])
        // The session visitor data also rides in the body's context object.
        assertTrue(transport.lastBody!!.contains("\"visitorData\":\"CgVisitorDataValue\""))
        assertTrue(transport.lastBody!!.contains("FEmusic_home"))
    }

    @Test
    fun anonymousHomeSendsNoAuthMaterial() {
        val transport = FixedTransport(body = Fixtures.load("browse_home.json"))
        YoutubeiClient(transport).home()
        val headers = transport.lastHeaders!!
        assertTrue(!headers.containsKey("Cookie"))
        assertTrue(!headers.containsKey("Authorization"))
        assertTrue(!headers.containsKey("X-Goog-Visitor-Id"))
        assertTrue(!transport.lastBody!!.contains("visitorData"))
    }

    @Test
    fun likedSongsBrowsesVlLmWithAuth() {
        val transport = FixedTransport(body = Fixtures.load("playlist.json"))
        val page = YoutubeiClient(transport).likedSongs(session)
        assertEquals("Never Gonna Give You Up", page.title)
        assertTrue(transport.lastBody!!.contains("\"browseId\":\"VLLM\""))
        assertEquals(session.cookieHeader(), transport.lastHeaders!!["Cookie"])
    }

    @Test
    fun libraryPlaylistsCollectsPlaylistItemsInOrder() {
        // The recorded home fixture's "Throwbacks" shelf is ten playlist
        // cards; served as the library response it exercises the same
        // section collection the real FEmusic_liked_playlists uses.
        val transport = FixedTransport(body = Fixtures.load("browse_home.json"))
        val playlists = YoutubeiClient(transport).libraryPlaylists(session)
        assertTrue(transport.lastBody!!.contains("FEmusic_liked_playlists"))
        assertEquals(10, playlists.size)
        assertTrue(playlists.all { it.browseId.isNotEmpty() && it.title.isNotEmpty() })
    }

    @Test
    fun fetchVisitorDataExtractsAndUnescapes() {
        val body = ")]}'\n{\"visitorData\":\"CgTest\\u003d\\u003dValue\"}"
        val client = YoutubeiClient(GetTransport(body = body))
        assertEquals("CgTest==Value", client.fetchVisitorData())
    }

    @Test
    fun fetchVisitorDataNeverThrows() {
        // ThrowingTransport does not implement GET: the default transport
        // method throws UnsupportedOperationException, which must read as
        // null here, never as an exception.
        assertNull(YoutubeiClient(ThrowingTransport()).fetchVisitorData())
        assertNull(
            YoutubeiClient(GetTransport(status = 404, body = "")).fetchVisitorData()
        )
        assertNull(
            YoutubeiClient(GetTransport(body = "no visitor data here")).fetchVisitorData()
        )
    }
}
