package dev.cyberdogs.offleash.core.playback

import dev.cyberdogs.offleash.core.FixedTransport
import dev.cyberdogs.offleash.core.Fixtures
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.api.YoutubeiContexts
import dev.cyberdogs.offleash.core.auth.AuthSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stream-quality selection (Wave 2).
 *
 * Fixture audio formats, read straight from the fixture contents:
 * - player_ios.json (recorded): itag 139 audio/mp4 @ 50152, itag 140
 *   audio/mp4 @ 130677, itag 249 audio/webm @ 49496, itag 250 audio/webm
 *   @ 65508, itag 251 audio/webm @ 136544
 * - derived_player_webremix.json (DERIVED-SYNTHETIC): itag 140
 *   audio/mp4 @ 131072, itag 774 audio/webm @ 256000
 * - derived_player_android.json (DERIVED-SYNTHETIC): itag 140
 *   audio/mp4 @ 131072, itag 251 audio/webm @ 139055
 *
 * StreamInfo carries no itag, so the chosen format is asserted through
 * the fixture URL's `itag=…&` parameter — every fixture URL is unique
 * per format.
 */
class StreamQualityTest {

    private val session = AuthSession(
        cookies = mapOf("SAPISID" to "sap", "SID" to "sid"),
    )

    private fun clientWith(body: String) = YoutubeiClient(FixedTransport(body = body))

    @Test
    fun iosHighPicksItag251() {
        val info = clientWith(Fixtures.load("player_ios.json"))
            .streamInfo("dQw4w9WgXcQ", quality = QualityPreference.HIGH)
        assertEquals(136544, info.bitrate)
        assertTrue("expected itag 251, url was ${info.url}", info.url.contains("itag=251&"))
    }

    @Test
    fun iosDefaultQualityIsHigh() {
        // No quality argument must resolve exactly like HIGH (the
        // Phase 1 rule), so existing callers see no change.
        val info = clientWith(Fixtures.load("player_ios.json")).streamInfo("dQw4w9WgXcQ")
        assertEquals(136544, info.bitrate)
        assertTrue(info.url.contains("itag=251&"))
    }

    @Test
    fun iosBalancedPicksHighestAtOrBelow160k() {
        // Every iOS audio format is at or below the 160k cap, so
        // BALANCED agrees with HIGH here: itag 251 @ 136544.
        val info = clientWith(Fixtures.load("player_ios.json"))
            .streamInfo("dQw4w9WgXcQ", quality = QualityPreference.BALANCED)
        assertEquals(136544, info.bitrate)
        assertTrue(info.url.contains("itag=251&"))
    }

    @Test
    fun iosLowPicksItag250MateriallySmallerThanHigh() {
        // Formats at or below the 80k cap: itag 139 (50152),
        // itag 249 (49496), itag 250 (65508) — LOW takes the best of
        // those, materially smaller than HIGH's itag 251.
        val info = clientWith(Fixtures.load("player_ios.json"))
            .streamInfo("dQw4w9WgXcQ", quality = QualityPreference.LOW)
        assertEquals(65508, info.bitrate)
        assertTrue("expected itag 250, url was ${info.url}", info.url.contains("itag=250&"))
        assertTrue("LOW must be materially smaller than HIGH", info.bitrate < 136544 / 2)
    }

    @Test
    fun webRemixHighPicksItag774() {
        val info = clientWith(Fixtures.load("derived_player_webremix.json"))
            .streamInfoAuthed("dQw4w9WgXcQ", session, quality = QualityPreference.HIGH)
        assertEquals(256000, info.bitrate)
        assertEquals("https://example.com/audio774.opus", info.url)
    }

    @Test
    fun webRemixBalancedPicksItag140UnderCap() {
        // itag 774 at 256000 is above the 160k cap, so BALANCED steps
        // down to itag 140 @ 131072.
        val info = clientWith(Fixtures.load("derived_player_webremix.json"))
            .streamInfoAuthed("dQw4w9WgXcQ", session, quality = QualityPreference.BALANCED)
        assertEquals(131072, info.bitrate)
        assertEquals("https://example.com/audio140.m4a", info.url)
    }

    @Test
    fun webRemixLowFallsBackToLowestWhenNoneUnderCap() {
        // Nothing is at or below the 80k cap, so LOW takes the
        // lowest-bitrate format available (itag 140 @ 131072).
        val info = clientWith(Fixtures.load("derived_player_webremix.json"))
            .streamInfoAuthed("dQw4w9WgXcQ", session, quality = QualityPreference.LOW)
        assertEquals(131072, info.bitrate)
        assertEquals("https://example.com/audio140.m4a", info.url)
    }

    @Test
    fun androidHighPicksItag251() {
        val info = clientWith(Fixtures.load("derived_player_android.json"))
            .streamInfo("pYAg_d-iQLk", YoutubeiContexts.ANDROID, QualityPreference.HIGH)
        assertEquals(139055, info.bitrate)
        assertEquals("https://example.com/android251.opus", info.url)
    }

    @Test
    fun androidBalancedAgreesWithHighWhenBothUnderCap() {
        // Both formats are at or below 160k, so BALANCED takes the
        // best of them — the same as HIGH (itag 251 @ 139055).
        val info = clientWith(Fixtures.load("derived_player_android.json"))
            .streamInfo("pYAg_d-iQLk", YoutubeiContexts.ANDROID, QualityPreference.BALANCED)
        assertEquals(139055, info.bitrate)
        assertEquals("https://example.com/android251.opus", info.url)
    }

    @Test
    fun androidLowFallsBackToLowestBitrate() {
        // Neither format is at or below 80k; LOW takes the
        // lowest-bitrate one (itag 140 @ 131072).
        val info = clientWith(Fixtures.load("derived_player_android.json"))
            .streamInfo("pYAg_d-iQLk", YoutubeiContexts.ANDROID, QualityPreference.LOW)
        assertEquals(131072, info.bitrate)
        assertEquals("https://example.com/android140.m4a", info.url)
    }

    @Test
    fun streamResolverAppliesQualityDirectly() {
        // The selector itself, outside the client: HIGH and LOW on the
        // recorded iOS player response.
        val body = Fixtures.load("player_ios.json")
        assertEquals(136544, StreamResolver.resolve(body, QualityPreference.HIGH).bitrate)
        assertEquals(65508, StreamResolver.resolve(body, QualityPreference.LOW).bitrate)
    }

    @Test
    fun playbackResolverThreadsQualityThroughAnonymousRoad() {
        val transport = FixedTransport(body = Fixtures.load("player_ios.json"))
        val resolved = PlaybackResolver(YoutubeiClient(transport))
            .resolve("dQw4w9WgXcQ", null, QualityPreference.LOW)
        assertEquals("anonymous", resolved.servedByRoad)
        assertEquals(65508, resolved.info.bitrate)
        assertTrue(resolved.info.url.contains("itag=250&"))
    }

    @Test
    fun playbackResolverThreadsQualityToAccountRoad() {
        val transport = FixedTransport(body = Fixtures.load("derived_player_webremix.json"))
        val resolved = PlaybackResolver(YoutubeiClient(transport))
            .resolve("dQw4w9WgXcQ", session, QualityPreference.BALANCED)
        assertEquals("account", resolved.servedByRoad)
        assertEquals(131072, resolved.info.bitrate)
        assertEquals("https://example.com/audio140.m4a", resolved.info.url)
    }
}
