package dev.cyberdogs.offleash.core.api

import dev.cyberdogs.offleash.core.FixedTransport
import dev.cyberdogs.offleash.core.Fixtures
import dev.cyberdogs.offleash.core.parse.YoutubeiParsers
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home header chips (Lane H): the recorded anonymous home fixture
 * carries an 11-chip `chipCloudRenderer` in its section-list header
 * that the Phase 1 sections parse dropped entirely. These tests pin
 * the extension that recovers them — labels in published order, each
 * with its own `FEmusic_home` browse endpoint — and pin that the
 * search response's filter chips (search endpoints, not browse
 * endpoints) are NOT home chips.
 */
class HomeChipsTest {

    @Test
    fun recordedHomeFixtureYieldsElevenChipsInPublishedOrder() {
        val root = Json.parseToJsonElement(Fixtures.load("browse_home.json"))
        val chips = YoutubeiParsers.parseHomeChips(root)
        assertEquals(
            listOf(
                "Podcasts", "Relax", "Feel good", "Energize", "Sad",
                "Romance", "Party", "Sleep", "Commute", "Workout", "Focus",
            ),
            chips.map { it.label },
        )
        assertTrue(chips.all { it.browseId == "FEmusic_home" })
        assertTrue(chips.all { !it.params.isNullOrBlank() })
        // Every chip is a distinct slice of the home feed.
        assertEquals(chips.size, chips.map { it.params }.distinct().size)
    }

    @Test
    fun searchFilterChipsAreNotHomeChips() {
        // The search fixture's chips navigate with a searchEndpoint;
        // none of them may leak into the home chip row.
        val root = Json.parseToJsonElement(Fixtures.load("search.json"))
        assertTrue(YoutubeiParsers.parseHomeChips(root).isEmpty())
    }

    @Test
    fun moodsFixtureHasNoHomeChips() {
        val root = Json.parseToJsonElement(Fixtures.load("browse_moods.json"))
        assertTrue(YoutubeiParsers.parseHomeChips(root).isEmpty())
    }

    @Test
    fun homeFeedParsesSectionsAndChipsFromOneBrowse() {
        val transport = FixedTransport(body = Fixtures.load("browse_home.json"))
        val feed = YoutubeiClient(transport).homeFeed()
        assertEquals("Quick picks", feed.sections[0].title)
        assertEquals(11, feed.chips.size)
        assertEquals("Podcasts", feed.chips.first().label)
        assertTrue(transport.lastBody!!.contains("FEmusic_home"))
    }

    @Test
    fun homeFeedWithAChipBrowsesTheChipsOwnEndpoint() {
        val transport = FixedTransport(body = Fixtures.load("browse_home.json"))
        val chips = YoutubeiParsers.parseHomeChips(
            Json.parseToJsonElement(Fixtures.load("browse_home.json")),
        )
        val relax = chips.first { it.label == "Relax" }
        YoutubeiClient(transport).homeFeed(chip = relax)
        assertTrue(transport.lastBody!!.contains("\"browseId\":\"FEmusic_home\""))
        assertTrue(transport.lastBody!!.contains(relax.params!!))
    }

    @Test
    fun homeFeedOAuthYieldsTheRecordedFixturesChips() {
        // Round 40: the token road's chips were asserted nowhere —
        // OAuthSurfacesTest reads its sections only — so a
        // post-parse transformation on this road (a helper in the
        // client, a file no contract pin reads) stripped the whole
        // chip cloud while every pin stayed green: the signed-in
        // home, whose load carries nothing (load() opts out of the
        // carry), never showed a chip row. The OAuth feed must
        // yield the same chips the cookie road is pinned to.
        val transport = FixedTransport(body = Fixtures.load("browse_home.json"))
        val feed = YoutubeiClient(transport).homeFeedOAuth("token")
        assertEquals(11, feed.chips.size)
        assertEquals(
            listOf(
                "Podcasts", "Relax", "Feel good", "Energize", "Sad",
                "Romance", "Party", "Sleep", "Commute", "Workout", "Focus",
            ),
            feed.chips.map { it.label },
        )
    }

    @Test
    fun homeFeedWithAChipStillYieldsTheResponsesChips() {
        // Round 40: the chip re-browse's RETURNED chips were
        // asserted nowhere either (the older test reads the
        // request body only) — a chip-conditional strip on either
        // road passed the whole suite. Both roads must hand the
        // response's chip cloud through.
        val chips = YoutubeiParsers.parseHomeChips(
            Json.parseToJsonElement(Fixtures.load("browse_home.json")),
        )
        val relax = chips.first { it.label == "Relax" }
        val cookieFeed = YoutubeiClient(
            FixedTransport(body = Fixtures.load("browse_home.json")),
        ).homeFeed(chip = relax)
        assertEquals(11, cookieFeed.chips.size)
        val oauthFeed = YoutubeiClient(
            FixedTransport(body = Fixtures.load("browse_home.json")),
        ).homeFeedOAuth("token", chip = relax)
        assertEquals(11, oauthFeed.chips.size)
    }

    @Test
    fun personalizedShelfTitlesParseGenericallyWithNoParserChange() {
        // Derived synthetic fixture (see its _comment): the generic
        // section parser already yields the personalized titles
        // verbatim, so the app renders them by title match — no
        // personalized-section parser branch exists or is needed.
        val root = Json.parseToJsonElement(Fixtures.load("derived_home_personalized.json"))
        val sections = YoutubeiParsers.parseSections(root)
        assertEquals(
            listOf("Listen again", "Forgotten favorites"),
            sections.map { it.title },
        )
        assertTrue(sections.all { it.items.size == 1 })
    }
}
