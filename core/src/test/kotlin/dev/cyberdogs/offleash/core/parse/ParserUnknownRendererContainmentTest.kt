package dev.cyberdogs.offleash.core.parse

import dev.cyberdogs.offleash.core.model.FeedItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unknown-renderer containment (bug sweep round 50, settling
 * round 49's disclosed richGridRenderer remainder).
 *
 * `richGridRenderer` has no dispatch in the parser, and round 49
 * left it documented-but-unpinned because no recorded response
 * from any endpoint the client calls contains one (zero of the
 * 23 fixtures). Round 50 probed the three positions an unknown
 * renderer can occupy — inside a shelf's items, at a section
 * list's contents level, and as a section-level entry wrapping
 * known items — against the shipped parser:
 *
 *  - inside a shelf, the rich grid dropped ONLY itself: both
 *    neighbouring known items parsed (the item it wrapped was
 *    lost with it, as its own content, not as a sibling);
 *  - at contents level, the sibling shelf parsed whole;
 *  - as the only contents entry it yielded no sections, and a
 *    malformed (non-object) richGridRenderer value beside a
 *    valid shelf parsed the shelf and did not throw.
 *
 * So the remainder stands as documented — no sibling loss, no
 * parse failure, no production change — and these tests pin the
 * containment property itself, so a future walker change that
 * lets an unknown renderer take its neighbours down (or throw)
 * fails here instead of shipping silently. The shapes are the
 * round-50 probes', with the probe outcomes as the expectations.
 */
class ParserUnknownRendererContainmentTest {

    private fun json(body: String): JsonElement = Json.parseToJsonElement(body)

    private fun twoRow(title: String, browseId: String): String =
        "{\"musicTwoRowItemRenderer\": {" +
            "\"title\": {\"runs\": [{\"text\": \"$title\"}]}," +
            "\"subtitle\": {\"runs\": [{\"text\": \"Artist\"}]}," +
            "\"navigationEndpoint\": {\"browseEndpoint\": {\"browseId\": \"$browseId\"," +
            "\"browseEndpointContextSupportedConfigs\": " +
            "{\"browseEndpointContextMusicConfig\": {\"pageType\": \"MUSIC_PAGE_TYPE_ALBUM\"}}}}," +
            "\"thumbnailRenderer\": {\"musicThumbnailRenderer\": {\"thumbnail\": " +
            "{\"thumbnails\": [{\"url\": \"https://i.ytimg.com/vi/x/default.jpg\"}]}}}" +
            "}}"

    private fun shelf(title: String, items: List<String>): String =
        "{\"musicShelfRenderer\": {" +
            "\"title\": {\"runs\": [{\"text\": \"$title\"}]}," +
            "\"contents\": [" + items.joinToString(",") + "]}}"

    private fun richGrid(items: List<String>): String =
        "{\"richGridRenderer\": {" +
            "\"contents\": [" + items.joinToString(",") + "]," +
            "\"header\": {\"feedFilterChipBarRenderer\": {\"contents\": []}}}}"

    private fun sectionList(contents: List<String>): String =
        "{\"contents\": {\"singleColumnBrowseResultsRenderer\": {\"tabs\": [" +
            "{\"tabRenderer\": {\"content\": {\"sectionListRenderer\": " +
            "{\"contents\": [" + contents.joinToString(",") + "]}}}}" +
            "]}}}"

    private fun albumTitles(sections: List<dev.cyberdogs.offleash.core.model.Section>): List<String> =
        sections.flatMap { it.items }
            .filterIsInstance<FeedItem.OfAlbum>()
            .map { it.album.title }

    @Test
    fun anUnknownRendererInsideAShelfDropsOnlyItself() {
        val body = json(
            sectionList(
                listOf(
                    shelf(
                        "Shelf B",
                        listOf(
                            twoRow("Known Album A", "MPREb_aaa"),
                            richGrid(listOf(twoRow("Known Album C", "MPREb_ccc"))),
                            twoRow("Known Album B", "MPREb_bbb"),
                        ),
                    ),
                ),
            ),
        )
        val sections = YoutubeiParsers.parseSections(body)
        assertEquals(1, sections.size)
        assertEquals(
            "both neighbours of the unknown renderer must survive it",
            listOf("Known Album A", "Known Album B"),
            albumTitles(sections),
        )
    }

    @Test
    fun anUnknownRendererAtContentsLevelLeavesItsSiblingSectionWhole() {
        val body = json(
            sectionList(
                listOf(
                    shelf("Shelf C", listOf(twoRow("Known Album A", "MPREb_aaa"))),
                    richGrid(
                        listOf(
                            twoRow("Known Album B", "MPREb_bbb"),
                            twoRow("Known Album C", "MPREb_ccc"),
                        ),
                    ),
                ),
            ),
        )
        val sections = YoutubeiParsers.parseSections(body)
        assertEquals(1, sections.size)
        assertEquals("Shelf C", sections[0].title)
        assertEquals(listOf("Known Album A"), albumTitles(sections))
    }

    @Test
    fun aMalformedUnknownRendererValueNeverFailsTheParse() {
        val body = json(
            sectionList(
                listOf(
                    shelf("Shelf OK", listOf(twoRow("Known Album A", "MPREb_aaa"))),
                    "{\"richGridRenderer\": \"junk\"}",
                ),
            ),
        )
        val sections = YoutubeiParsers.parseSections(body)
        assertEquals(1, sections.size)
        assertEquals(listOf("Known Album A"), albumTitles(sections))
        // And as the only entry, an unknown container yields the
        // honest empty parse rather than recovered inventions.
        val alone = YoutubeiParsers.parseSections(
            json(sectionList(listOf(richGrid(listOf(twoRow("Known Album A", "MPREb_aaa")))))),
        )
        assertEquals(0, alone.size)
    }
}
