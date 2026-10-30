package dev.cyberdogs.offleash.core.parse

import dev.cyberdogs.offleash.core.model.FeedItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Scale shapes and the section-walk recursion (bug sweep round 2,
 * charters 1 & 4): a 10,000-track playlist page, a 500-section home
 * feed, unicode extremes through the parsers, absurd numbers that
 * must read as unknown rather than negative — and the nested
 * section-list chains whose mutual recursion between
 * walkSections/processSectionContents used to reset the round-1
 * depth cap to zero on every hop, so the cap never fired and a
 * hostile body overflowed the stack.
 */
class ParserScaleRecursionTest {

    private fun <T> armored(label: String, block: () -> T): T {
        try {
            return block()
        } catch (t: Throwable) {
            fail("$label escaped with ${t.javaClass.name}: ${t.message}")
            throw AssertionError("unreachable")
        }
    }

    private fun sectionListWrap(child: JsonElement): JsonObject =
        JsonObject(
            mapOf(
                "sectionListRenderer" to JsonObject(
                    mapOf("contents" to JsonArray(listOf(child)))
                )
            )
        )

    @Test
    fun nestedSectionListChainsStopAtTheDepthCap() {
        // Built iteratively (no parser recursion involved): 10,000
        // nested section lists around an empty innermost list.
        var node: JsonElement =
            JsonObject(
                mapOf(
                    "sectionListRenderer" to JsonObject(
                        mapOf("contents" to JsonArray(emptyList()))
                    )
                )
            )
        repeat(10_000) { node = sectionListWrap(node) }
        assertEquals(
            emptyList<Any>(),
            armored("pure chain") { YoutubeiParsers.parseSections(node) },
        )

        // The alternating shape: an unknown wrapper between every
        // pair of section lists — the hop that reset the counter
        // through the else branch of processSectionContents.
        var alt: JsonElement = node
        repeat(5_000) {
            alt = JsonObject(mapOf("mysteryRenderer" to alt))
            alt = sectionListWrap(alt)
        }
        armored("alternating chain") { YoutubeiParsers.parseSections(alt) }
    }

    @Test
    fun tenThousandTrackPlaylistParsesWhole() {
        val sb = StringBuilder()
        sb.append("""{"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[""")
        for (i in 0 until 10_000) {
            if (i > 0) sb.append(",")
            sb.append(
                """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song $i"}]}}}],"playlistItemData":{"videoId":"vid$i"}}}"""
            )
        }
        sb.append("""]}}]}}}""")
        val root = Json.parseToJsonElement(sb.toString())
        val page = armored("10k playlist") { YoutubeiParsers.parsePlaylist(root, "VLbig") }
        assertEquals(10_000, page.tracks.size)
        assertEquals("vid0", page.tracks.first().videoId)
        assertEquals("vid9999", page.tracks.last().videoId)
        assertTrue(page.tracks.all { it.durationSeconds == null || it.durationSeconds!! >= 0 })
    }

    @Test
    fun fiveHundredSectionHomeParsesWhole() {
        val sb = StringBuilder()
        sb.append("""{"sectionListRenderer":{"contents":[""")
        for (i in 0 until 500) {
            if (i > 0) sb.append(",")
            sb.append(
                """{"musicShelfRenderer":{"title":{"runs":[{"text":"Section $i"}]},"contents":[{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song $i"}]}}}],"playlistItemData":{"videoId":"s$i"}}}]}}"""
            )
        }
        sb.append("""]}}""")
        val root = Json.parseToJsonElement(sb.toString())
        val sections = armored("500 sections") { YoutubeiParsers.parseSections(root) }
        assertEquals(500, sections.size)
        assertEquals("Section 499", sections.last().title)
    }

    @Test
    fun unicodeExtremesSurviveIntoTheModel() {
        val longTitle = "🎵".repeat(2500) + "أغنية ‏‏‎" + "x".repeat(7500)
        fun flexCol(vararg runTexts: String): JsonObject = buildJsonObject {
            put(
                "musicResponsiveListItemFlexColumnRenderer",
                buildJsonObject {
                    put(
                        "text",
                        buildJsonObject {
                            put(
                                "runs",
                                buildJsonArray {
                                    for (runText in runTexts) {
                                        add(buildJsonObject { put("text", runText) })
                                    }
                                },
                            )
                        },
                    )
                },
            )
        }
        val row = buildJsonObject {
            put(
                "musicResponsiveListItemRenderer",
                buildJsonObject {
                    put(
                        "flexColumns",
                        buildJsonArray {
                            add(flexCol(longTitle))
                            add(flexCol("Björk", " • ", "歌 手"))
                        },
                    )
                    put(
                        "playlistItemData",
                        buildJsonObject { put("videoId", "uni1") },
                    )
                },
            )
        }
        val root = buildJsonObject {
            put(
                "sectionListRenderer",
                buildJsonObject {
                    put(
                        "contents",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put(
                                        "musicShelfRenderer",
                                        buildJsonObject {
                                            put("contents", buildJsonArray { add(row) })
                                        },
                                    )
                                }
                            )
                        },
                    )
                },
            )
        }
        val tracks = armored("unicode") { YoutubeiParsers.parseSections(root) }
            .flatMap { it.items }
            .filterIsInstance<FeedItem.OfTrack>()
            .map { it.track }
        assertEquals(1, tracks.size)
        assertEquals(longTitle, tracks[0].title)
        assertEquals(listOf("Björk", "歌 手"), tracks[0].artists)
    }

    @Test
    fun missingThumbnailsAndArtistsEverywhereIsHonest() {
        val sb = StringBuilder()
        sb.append("""{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[""")
        for (i in 0 until 50) {
            if (i > 0) sb.append(",")
            sb.append(
                """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Bare $i"}]}}}],"playlistItemData":{"videoId":"bare$i"}}}"""
            )
        }
        sb.append("""]}}]}}""")
        val root = Json.parseToJsonElement(sb.toString())
        val tracks = armored("bare rows") { YoutubeiParsers.parseSections(root) }
            .flatMap { it.items }
            .filterIsInstance<FeedItem.OfTrack>()
            .map { it.track }
        assertEquals(50, tracks.size)
        assertTrue(tracks.all { it.thumbnailUrl == null && it.artists.isEmpty() })
    }

    @Test
    fun durationOverflowReadsAsUnknownNeverNegative() {
        assertEquals(3723, parseDurationSeconds("1:02:03"))
        assertEquals(0, parseDurationSeconds("0:00"))
        // Exactly Int.MAX_VALUE seconds is representable…
        assertEquals(Int.MAX_VALUE, parseDurationSeconds("596523:14:07"))
        // …one second past it, and the plain absurd, are unknown.
        assertNull(parseDurationSeconds("596523:14:08"))
        assertNull(parseDurationSeconds("40000000:00"))
        assertNull(parseDurationSeconds("99999999999999999999:00"))
    }

    @Test
    fun trackWithAnAbsurdDurationGetsNoDuration() {
        val body = """{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[
            {"musicResponsiveListItemRenderer":{
                "flexColumns":[
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Long Song"}]}}},
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"},{"text":" • "},{"text":"40000000:00"}]}}}
                ],
                "playlistItemData":{"videoId":"longvid"}
            }}]}}]}}"""
        val root = Json.parseToJsonElement(body)
        val tracks = YoutubeiParsers.parseSections(root)
            .flatMap { it.items }
            .filterIsInstance<FeedItem.OfTrack>()
            .map { it.track }
        assertEquals(1, tracks.size)
        assertNull(tracks[0].durationSeconds)
    }

    @Test
    fun absurdTrackCountReadsAsUnknown() {
        val body = """{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[
            {"musicTwoRowItemRenderer":{
                "title":{"runs":[{"text":"Big List"}]},
                "subtitle":{"runs":[{"text":"Playlist"},{"text":" • "},{"text":"The Author"},{"text":" • "},{"text":"2,147,483,648 songs"}]},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"VLbig","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}}}
            }}]}}]}}"""
        val root = Json.parseToJsonElement(body)
        val playlists = YoutubeiParsers.parseSections(root)
            .flatMap { it.items }
            .filterIsInstance<FeedItem.OfPlaylist>()
            .map { it.playlist }
        assertEquals(1, playlists.size)
        assertEquals("The Author", playlists[0].author)
        assertNull(playlists[0].trackCount)
    }
}
