package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.FixedTransport
import dev.cyberdogs.offleash.core.Fixtures
import dev.cyberdogs.offleash.core.GetTransport
import dev.cyberdogs.offleash.core.RoutingTransport
import dev.cyberdogs.offleash.core.ThrowingTransport
import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.model.LyricsResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lyrics (Wave 2): YouTube's own lyrics via `next` + browse, and
 * synced lyrics via LRCLIB.
 *
 * The `derived_next_lyrics.json`, `derived_next_no_lyrics.json`,
 * `derived_lyrics_browse.json` and `derived_lrclib_synced.json`
 * fixtures are DERIVED-SYNTHETIC: hand-built to the documented
 * response shapes (the recorded next.json proved the real Lyrics-tab
 * shape — a tabRenderer titled "Lyrics" with a browseEndpoint carrying
 * a browseId — and the album fixture proved the
 * musicDescriptionShelfRenderer description shape). No real account or
 * recorded lyric data exists; every lyric line in the fixtures is
 * invented for the tests.
 */
class LyricsTest {

    private val session = AuthSession(
        cookies = mapOf("SAPISID" to "sap", "SID" to "sid"),
    )

    /** Answers next/browse by URL path, recording everything asked. */
    private fun lyricsTransport() = RoutingTransport { url, _, body ->
        when {
            url.contains("/v1/next?") ->
                HttpResponse(200, Fixtures.load("derived_next_lyrics.json"))
            url.contains("/v1/browse?") -> {
                assertTrue(
                    "the lyrics browse must carry the tab's browseId, body was $body",
                    body!!.contains("\"browseId\":\"MPLYderivedLyrics\"")
                )
                HttpResponse(200, Fixtures.load("derived_lyrics_browse.json"))
            }
            else -> HttpResponse(500, "{}")
        }
    }

    @Test
    fun youtubeLyricsFoundFromNextTabAndBrowse() {
        val transport = lyricsTransport()
        val result = YoutubeiClient(transport).lyrics("derivedVideoId")
        val found = result as? LyricsResult.Found
            ?: throw AssertionError("expected Found, was $result")
        assertEquals("YouTube Music", found.source)
        assertEquals(
            listOf(
                "Streetlight halos on the overpass",
                "Counting miles in a borrowed car",
                "Every exit sign says almost home",
                "But the radio keeps pulling me far",
            ),
            found.lines.map { it.text }
        )
        // Plain YouTube lyrics carry no timing.
        assertTrue(found.lines.all { it.startMs == null })
        // One next call plus one browse call, both on WEB_REMIX.
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests.all { it.url.startsWith("https://music.youtube.com/") })
    }

    @Test
    fun youtubeLyricsWithAuthSendsSessionOnNext() {
        val transport = RoutingTransport { url, _, _ ->
            if (url.contains("/v1/next?")) {
                HttpResponse(200, Fixtures.load("derived_next_no_lyrics.json"))
            } else {
                HttpResponse(500, "{}")
            }
        }
        YoutubeiClient(transport).lyrics("derivedVideoId", session)
        assertEquals(session.cookieHeader(), transport.requests[0].headers["Cookie"])
        assertTrue(transport.requests[0].body!!.contains("derivedVideoId"))
    }

    @Test
    fun youtubeLyricsUnavailableWhenNoTab() {
        // The derived no-tab fixture carries only Up next + Related:
        // there is nothing to browse.
        val transport = RoutingTransport { url, _, _ ->
            HttpResponse(200, Fixtures.load("derived_next_no_lyrics.json"))
        }
        val result = YoutubeiClient(transport).lyrics("derivedVideoId")
        val unavailable = result as? LyricsResult.Unavailable
            ?: throw AssertionError("expected Unavailable, was $result")
        assertEquals("YouTube offers no lyrics for this track", unavailable.reason)
        // No browse call may follow a missing tab.
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun youtubeLyricsUnavailableWhenTransportFails() {
        // A dead network reads as Unavailable with the plain failure
        // message — lyrics failure must never throw, and never break
        // the playback path waiting on it.
        val result = YoutubeiClient(ThrowingTransport()).lyrics("derivedVideoId")
        val unavailable = result as? LyricsResult.Unavailable
            ?: throw AssertionError("expected Unavailable, was $result")
        assertTrue(unavailable.reason.startsWith("Couldn't reach YouTube Music"))
    }

    @Test
    fun syncedLyricsParsesTimedLines() {
        val transport = GetTransport(body = Fixtures.load("derived_lrclib_synced.json"))
        val lines = YoutubeiClient(transport).syncedLyrics(
            "Midnight Wire", "The Test Patterns", 213
        )!!
        assertEquals(2, lines.size)
        assertEquals("First timed line", lines[0].text)
        assertEquals(12340L, lines[0].startMs) // [00:12.34] — centiseconds
        assertEquals("Second timed line", lines[1].text)
        assertEquals(65250L, lines[1].startMs) // [01:05.250] — milliseconds

        val url = transport.lastGetUrl!!
        assertTrue(url.startsWith("https://lrclib.net/api/get?"))
        assertTrue(url.contains("track_name=Midnight+Wire"))
        assertTrue(url.contains("artist_name=The+Test+Patterns"))
        assertTrue(url.contains("duration=213"))
        // The third-party call carries no headers at all — no session
        // material ever leaves for LRCLIB.
        assertEquals(emptyMap<String, String>(), transport.lastGetHeaders)
    }

    @Test
    fun syncedLyricsReturnsNullOnEmptyResult() {
        assertNull(
            YoutubeiClient(GetTransport(body = "{}")).syncedLyrics("Nothing", "Nobody", null)
        )
        assertNull(
            YoutubeiClient(GetTransport(body = """{"syncedLyrics":""}"""))
                .syncedLyrics("Nothing", "Nobody", null)
        )
        assertNull(
            YoutubeiClient(GetTransport(status = 404, body = "not found"))
                .syncedLyrics("Nothing", "Nobody", null)
        )
    }

    @Test
    fun syncedLyricsReturnsNullOnThrowingTransport() {
        val client = YoutubeiClient(GetTransport(failure = RuntimeException("offline")))
        assertNull(client.syncedLyrics("Midnight Wire", "The Test Patterns", 213))
    }

    @Test
    fun syncedLyricsReturnsNullOnMalformedBody() {
        val client = YoutubeiClient(GetTransport(body = "this is not json"))
        assertNull(client.syncedLyrics("Midnight Wire", "The Test Patterns", 213))
    }
}
