package dev.cyberdogs.offleash.core.parse

import dev.cyberdogs.offleash.core.model.FeedItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Renderer-dispatch census (bug sweep round 49). R48's F5–F8
 * rested on synthetic probes for shapes the recorded corpus
 * lacks; this class generalizes that pass over EVERY renderer
 * the parser dispatches on. Each test below pins one dispatch
 * case that no recorded fixture exercises with a non-empty
 * extraction, with a shape-faithful synthetic body whose parse
 * outcome was first established by a probe against the shipped
 * parser (all correct — these are coverage pins, not defect
 * fixes):
 *
 *  - the `musicDetailHeaderRenderer` album/playlist header
 *    fallback (no fixture carries the renderer at all);
 *  - the `musicImmersiveHeaderRenderer` artist header fallback
 *    (likewise absent from the corpus);
 *  - the `MUSIC_PAGE_TYPE_USER_CHANNEL` and
 *    `MUSIC_PAGE_TYPE_NON_MUSIC` page types, on BOTH row
 *    parsers (responsive and two-row);
 *  - the podcast page-type drop on both row parsers — pinned
 *    as intended: the parser's strategy is that podcasts are
 *    not feed items (see parseResponsiveListItem);
 *  - the two-row watch-only track path (no browse endpoint
 *    anywhere on the card);
 *  - the home chip whose browse endpoint lives only on
 *    `onSelectedCommand`;
 *  - the `musicShelfContinuation` /
 *    `musicPlaylistShelfContinuation` arms INSIDE a section
 *    list's contents (the continuation fixtures exercise the
 *    bare-shelf walk arm instead), and a section list nested
 *    inside another section list's contents.
 *
 * Cases the parser does not dispatch on at all — and which no
 * recorded response from any endpoint the client calls
 * contains — are recorded in the round-49 lane report
 * (richGridRenderer, reelItemRenderer) rather than pinned
 * here.
 */
class ParserDispatchCensusTest {

    private fun json(body: String): JsonElement = Json.parseToJsonElement(body)

    private fun runs(vararg texts: String): String =
        texts.joinToString(",") { "{\"text\": \"$it\"}" }

    private fun config(pageType: String?): String =
        if (pageType == null) {
            ""
        } else {
            ",\"browseEndpointContextSupportedConfigs\": " +
                "{\"browseEndpointContextMusicConfig\": {\"pageType\": \"$pageType\"}}"
        }

    private fun responsiveRow(
        browseId: String,
        pageType: String?,
        title: String,
        byline: String,
        videoId: String? = null,
    ): String {
        val pid = if (videoId == null) "" else "\"playlistItemData\": {\"videoId\": \"$videoId\"}, "
        return "{\"musicResponsiveListItemRenderer\": {" + pid +
            "\"flexColumns\": [" +
            "{\"musicResponsiveListItemFlexColumnRenderer\": {\"text\": {\"runs\": [" + runs(title) + "]}}}," +
            "{\"musicResponsiveListItemFlexColumnRenderer\": {\"text\": {\"runs\": [" + runs(byline) + "]}}}" +
            "]," +
            "\"navigationEndpoint\": {\"browseEndpoint\": {\"browseId\": \"$browseId\"" + config(pageType) + "}}" +
            "}}"
    }

    private fun twoRow(
        browseId: String,
        pageType: String?,
        title: String,
        subtitle: String,
    ): String =
        "{\"musicTwoRowItemRenderer\": {" +
            "\"title\": {\"runs\": [" + runs(title) + "]}," +
            "\"subtitle\": {\"runs\": [" + runs(subtitle) + "]}," +
            "\"navigationEndpoint\": {\"browseEndpoint\": {\"browseId\": \"$browseId\"" + config(pageType) + "}}" +
            "}}"

    @Test
    fun albumParsesFromADetailHeaderRendererAlone() {
        val body = json(
            "{\"header\": {\"musicDetailHeaderRenderer\": {" +
                "\"title\": {\"runs\": [" + runs("Detail Title") + "]}," +
                "\"subtitle\": {\"runs\": [" + runs("Album", " \u2022 ", "1999") + "]}," +
                "\"straplineTextOne\": {\"runs\": [" + runs("Detail Artist") + "]}" +
                "}}," +
                "\"contents\": {\"sectionListRenderer\": {\"contents\": [" +
                "{\"musicShelfRenderer\": {\"contents\": [" +
                responsiveRow("x", null, "T1", "A1", "vid00000001") +
                "]}}" +
                "]}}}",
        )
        val page = YoutubeiParsers.parseAlbum(body, "MPREb_detail")
        assertEquals("Detail Title", page.album.title)
        assertEquals("Detail Artist", page.album.artist)
        assertEquals("1999", page.album.year)
        assertEquals(listOf("vid00000001"), page.tracks.map { it.videoId })
    }

    @Test
    fun artistParsesFromAnImmersiveHeaderRendererAlone() {
        val body = json(
            "{\"header\": {\"musicImmersiveHeaderRenderer\": {" +
                "\"title\": {\"runs\": [" + runs("Immersive Artist") + "]}" +
                "}}," +
                "\"contents\": {\"sectionListRenderer\": {\"contents\": [" +
                "{\"musicShelfRenderer\": {\"title\": {\"runs\": [" + runs("Songs") + "]}, \"contents\": [" +
                responsiveRow("y", null, "S1", "B1", "vid00000002") +
                "]}}" +
                "]}}}",
        )
        val page = YoutubeiParsers.parseArtist(body)
        assertEquals("Immersive Artist", page.name)
        assertEquals(1, page.sections.size)
        assertEquals("Songs", page.sections[0].title)
        assertEquals(1, page.sections[0].items.size)
    }

    @Test
    fun channelAndNonMusicPageTypesParseAsArtistsOnBothRowShapes() {
        for (pageType in listOf("MUSIC_PAGE_TYPE_USER_CHANNEL", "MUSIC_PAGE_TYPE_NON_MUSIC")) {
            val responsive = YoutubeiParsers.parseFeedItem(
                json(responsiveRow("UCabc", pageType, "Chan", "Artist")),
            )
            assertTrue(
                "responsive $pageType must parse as an artist",
                responsive is FeedItem.OfArtist,
            )
            assertEquals(
                "UCabc",
                (responsive as FeedItem.OfArtist).artist.browseId,
            )
            val card = YoutubeiParsers.parseFeedItem(
                json(twoRow("UCabc", pageType, "Chan", "Artist")),
            )
            assertTrue(
                "two-row $pageType must parse as an artist",
                card is FeedItem.OfArtist,
            )
            assertEquals(
                "UCabc",
                (card as FeedItem.OfArtist).artist.browseId,
            )
        }
    }

    @Test
    fun podcastPageTypeIsDroppedOnBothRowShapes() {
        assertNull(
            YoutubeiParsers.parseFeedItem(
                json(responsiveRow("VLpod", "MUSIC_PAGE_TYPE_PODCAST", "Pod", "Host")),
            ),
        )
        assertNull(
            YoutubeiParsers.parseFeedItem(
                json(twoRow("VLpod", "MUSIC_PAGE_TYPE_PODCAST", "Pod", "Host")),
            ),
        )
    }

    @Test
    fun twoRowWatchOnlyItemParsesAsATrack() {
        val item = YoutubeiParsers.parseFeedItem(
            json(
                "{\"musicTwoRowItemRenderer\": {" +
                    "\"title\": {\"runs\": [" + runs("Loose Song") + "]}," +
                    "\"subtitle\": {\"runs\": [" + runs("Some Artist", " \u2022 ", "3:33") + "]}," +
                    "\"navigationEndpoint\": {\"watchEndpoint\": {\"videoId\": \"vidwatch001\"}}" +
                    "}}",
            ),
        )
        assertTrue(item is FeedItem.OfTrack)
        val track = (item as FeedItem.OfTrack).track
        assertEquals("vidwatch001", track.videoId)
        assertEquals("Loose Song", track.title)
        assertEquals(listOf("Some Artist"), track.artists)
        assertEquals(213, track.durationSeconds)
    }

    @Test
    fun homeChipFallsBackToTheOnSelectedCommandBrowseEndpoint() {
        val body = json(
            "{\"contents\": {\"sectionListRenderer\": {\"header\": {\"chipCloudRenderer\": {\"chips\": [" +
                "{\"chipCloudChipRenderer\": {" +
                "\"text\": {\"runs\": [" + runs("Workout") + "]}," +
                "\"onSelectedCommand\": {\"browseEndpoint\": {\"browseId\": \"FEmusic_home\", \"params\": \"chipparams1\"}}" +
                "}}" +
                "]}}, \"contents\": []}}}",
        )
        val chips = YoutubeiParsers.parseHomeChips(body)
        assertEquals(1, chips.size)
        assertEquals("Workout", chips[0].label)
        assertEquals("FEmusic_home", chips[0].browseId)
        assertEquals("chipparams1", chips[0].params)
    }

    @Test
    fun continuationArmsInsideASectionListParseAsUntitledChunks() {
        for (key in listOf("musicShelfContinuation", "musicPlaylistShelfContinuation")) {
            val body = json(
                "{\"contents\": {\"sectionListRenderer\": {\"contents\": [" +
                    "{\"$key\": {\"contents\": [" +
                    responsiveRow("z", null, "Chunk Song", "Artist", "vidcont0001") +
                    "]}}" +
                    "]}}}",
            )
            val sections = YoutubeiParsers.parseSections(body)
            assertEquals("in-list $key must yield one chunk section", 1, sections.size)
            assertNull(sections[0].title)
            assertEquals(1, sections[0].items.size)
        }
        val nested = json(
            "{\"contents\": {\"sectionListRenderer\": {\"contents\": [" +
                "{\"sectionListRenderer\": {\"contents\": [" +
                "{\"musicShelfRenderer\": {\"title\": {\"runs\": [" + runs("Inner Shelf") + "]}, \"contents\": [" +
                responsiveRow("z", null, "Nested Song", "Artist", "vidnest0001") +
                "]}}" +
                "]}}" +
                "]}}}",
        )
        val sections = YoutubeiParsers.parseSections(nested)
        assertEquals(1, sections.size)
        assertEquals("Inner Shelf", sections[0].title)
        assertEquals(1, sections[0].items.size)
    }
}
