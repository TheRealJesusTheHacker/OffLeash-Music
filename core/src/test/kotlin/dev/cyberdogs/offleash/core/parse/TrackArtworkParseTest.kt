package dev.cyberdogs.offleash.core.parse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The artwork discipline (0.9.0, owner order 2026-11-04: "use the
 * thumbnails of each song"): every parsed track carries TWO
 * references — the row-sized pick (the smallest variant that is
 * still at least 512 px wide, the codebase's standing pick) for
 * rows and rails, and the largest variant as the hero reference,
 * so the player's big surface decodes genuinely high-resolution
 * art instead of an upscaled row thumbnail.
 */
class TrackArtworkParseTest {

    private fun responsiveItem(thumbs: List<Pair<String, Int>>): kotlinx.serialization.json.JsonObject {
        val thumbsJson = thumbs.joinToString(",") { (url, w) ->
            """{"url": "$url", "width": $w, "height": $w}"""
        }
        val json = """
            {
              "flexColumns": [
                {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
                  {"text": "Blue Monday", "navigationEndpoint": {"watchEndpoint": {"videoId": "abc123"}}}
                ]}}},
                {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
                  {"text": "New Order"}
                ]}}}
              ],
              "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [$thumbsJson]}}}
            }
        """.trimIndent()
        return Json.parseToJsonElement(json).jsonObject
    }

    @Test
    fun trackCarriesRowSizedAndHeroReferences() {
        val item = responsiveItem(
            listOf(
                "https://img/s60.jpg" to 60,
                "https://img/s540.jpg" to 540,
                "https://img/s1200.jpg" to 1200,
            ),
        )
        val track = YoutubeiParsers.parseTrackFromResponsive(item)
        requireNotNull(track)
        assertEquals("https://img/s540.jpg", track.thumbnailUrl)
        assertEquals("https://img/s1200.jpg", track.heroThumbnailUrl)
    }

    @Test
    fun singleVariantServesBothReferences() {
        val item = responsiveItem(listOf("https://img/only.jpg" to 226))
        val track = YoutubeiParsers.parseTrackFromResponsive(item)
        requireNotNull(track)
        assertEquals("https://img/only.jpg", track.thumbnailUrl)
        assertEquals("https://img/only.jpg", track.heroThumbnailUrl)
    }
}
