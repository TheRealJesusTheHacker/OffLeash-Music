package dev.cyberdogs.offleash.core.parse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Parser armor (bug sweep round 1): drifted/hostile server data must
 * degrade to the honest empty/result, never to an escaping
 * NPE/ClassCastException/IndexOutOfBounds. Every surface parser is
 * fed the same battery of malformed variants — truncated shapes,
 * wrong types in renderer slots, null renderers, empty renderer
 * lists, deeply nested junk — plus the one shape that provably
 * crashed before this sweep (a renderer key carrying a non-object
 * value, force-unwrapped by `!!` in the section walker).
 */
class ParserArmorTest {

    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    /** Runs [block]; any escaping Throwable fails the test by name. */
    private fun <T> armored(label: String, block: () -> T): T {
        try {
            return block()
        } catch (t: Throwable) {
            fail("$label escaped with ${t.javaClass.name}: ${t.message}")
            throw AssertionError("unreachable")
        }
    }

    private val hostileBodies = listOf(
        "{}",
        "[]",
        "42",
        "\"just a string\"",
        "null",
        """{"contents": "nope"}""",
        """{"contents": [1, "two", null, true]}""",
        """{"sectionListRenderer": "junk"}""",
        """{"sectionListRenderer": {"contents": "junk"}}""",
        """{"sectionListRenderer": {"contents": [null, 42, {"musicShelfRenderer": null}]}}""",
        """{"header": {"musicVisualHeaderRenderer": []}}""",
        """{"header": []}""",
        """{"chipCloudRenderer": {"chips": "no"}}""",
        """{"chipCloudRenderer": {"chips": [{"chipCloudChipRenderer": "junk"}]}}""",
        """{"musicNavigationButtonRenderer": {"buttonText": 42}}""",
        """{"playlistPanelVideoRenderer": {"videoId": 7, "title": []}}""",
    )

    @Test
    fun everySurfaceSurvivesTheHostileBattery() {
        for (body in hostileBodies) {
            val root = parse(body)
            armored("parseSections($body)") { YoutubeiParsers.parseSections(root) }
            armored("parseHome($body)") { YoutubeiParsers.parseHome(root) }
            armored("parseHomeChips($body)") { YoutubeiParsers.parseHomeChips(root) }
            armored("parseSearch($body)") { YoutubeiParsers.parseSearch(root) }
            armored("parseMoods($body)") { YoutubeiParsers.parseMoods(root) }
            armored("parseAlbum($body)") { YoutubeiParsers.parseAlbum(root, "MPREb_test") }
            armored("parsePlaylist($body)") { YoutubeiParsers.parsePlaylist(root, "VL_test") }
            armored("parseArtist($body)") { YoutubeiParsers.parseArtist(root) }
            armored("parseNextTracks($body)") { YoutubeiParsers.parseNextTracks(root) }
            armored("parseContinuationToken($body)") { YoutubeiParsers.parseContinuationToken(root) }
            armored("parsePlaylistContinuation($body)") {
                YoutubeiParsers.parsePlaylistContinuation(root)
            }
        }
    }

    @Test
    fun mistypedShelfRenderersYieldNoSectionsInsteadOfCrashing() {
        // The pre-sweep escape: containsKey() was true, obj() was
        // null, and `!!` threw NullPointerException to the UI.
        val root = parse(
            """{"sectionListRenderer": {"contents": [
                {"musicCarouselShelfRenderer": "junk"},
                {"musicShelfRenderer": 42},
                {"musicCardShelfRenderer": []},
                {"itemSectionRenderer": null}
            ]}}""",
        )
        assertEquals(emptyList<Any>(), armored("sections") { YoutubeiParsers.parseSections(root) })
    }

    @Test
    fun wrongTypesInsideATrackRowAreNotFatal() {
        // Title runs carrying numbers/nulls, a numeric videoId, a
        // string where the thumbnails array belongs. The contract
        // pinned here is the JsonNav one: scalar primitives coerce
        // to their text form (str() reads contentOrNull), structured
        // slots mistyped as scalars read as absent (no thumbnail),
        // and NOTHING escapes — the well-formed sibling row parses
        // exactly as it should. A coerced id like "42" can only fail
        // later as an honest playback refusal, never as a crash.
        val root = parse(
            """{"sectionListRenderer": {"contents": [
                {"musicShelfRenderer": {"contents": [
                    {"musicResponsiveListItemRenderer": {
                        "flexColumns": [
                            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": 7}, null]}}}
                        ],
                        "playlistItemData": {"videoId": 42},
                        "thumbnail": {"thumbnails": "junk"}
                    }},
                    {"musicResponsiveListItemRenderer": {
                        "flexColumns": [
                            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Good"}]}}}
                        ],
                        "playlistItemData": {"videoId": "goodVideoId1"}
                    }}
                ]}}
            ]}}""",
        )
        val sections = armored("sections") { YoutubeiParsers.parseSections(root) }
        val tracks = sections.flatMap { it.items }
            .filterIsInstance<dev.cyberdogs.offleash.core.model.FeedItem.OfTrack>()
            .map { it.track }
        assertEquals(2, tracks.size)
        assertEquals("42", tracks[0].videoId)
        assertEquals("7", tracks[0].title)
        assertEquals(null, tracks[0].thumbnailUrl)
        assertEquals("goodVideoId1", tracks[1].videoId)
        assertEquals("Good", tracks[1].title)
    }

    @Test
    fun deeplyNestedJunkDegradesInsteadOfOverflowing() {
        // 150 levels of junk wrapping a section list: past the tree
        // walk cap the content is honestly unreachable, and the walk
        // must return (empty) rather than overflow the stack. The
        // shallower twin proves the cap did not eat real content.
        fun nested(depth: Int): String {
            val sb = StringBuilder()
            repeat(depth) { sb.append("{\"wrap\":") }
            sb.append("""{"sectionListRenderer": {"contents": []}}""")
            repeat(depth) { sb.append("}") }
            return sb.toString()
        }
        assertEquals(
            emptyList<Any>(),
            armored("deep sections") { YoutubeiParsers.parseSections(parse(nested(150))) },
        )
        armored("deep findAll") { findAll(parse(nested(150)), "sectionListRenderer") }
        armored("deep findFirst") { findFirst(parse(nested(150)), "sectionListRenderer") }
        val shallow = parse(nested(10))
        assertEquals(1, findAll(shallow, "sectionListRenderer").size)
        assertTrue(findFirst(shallow, "sectionListRenderer") != null)
    }

    @Test
    fun emptyRendererListsAreHonestEmpties() {
        val root = parse(
            """{"sectionListRenderer": {"contents": [
                {"musicShelfRenderer": {"contents": []}},
                {"musicCarouselShelfRenderer": {"contents": []}},
                {"itemSectionRenderer": {"contents": []}}
            ]}}""",
        )
        assertEquals(emptyList<Any>(), YoutubeiParsers.parseSections(root))
        assertEquals(emptyList<Any>(), YoutubeiParsers.parseHomeChips(root))
        assertEquals(emptyList<Any>(), YoutubeiParsers.parseNextTracks(root))
    }
}
