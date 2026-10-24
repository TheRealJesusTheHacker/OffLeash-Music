package dev.cyberdogs.offleash.core.playback

import dev.cyberdogs.offleash.core.Fixtures
import dev.cyberdogs.offleash.core.error.NoPlayableStream
import dev.cyberdogs.offleash.core.error.ParseFailure
import dev.cyberdogs.offleash.core.error.PlayerRefused
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StreamResolverTest {

    @Test
    fun iosFixtureResolvesToBestDirectAudio() {
        val info = StreamResolver.resolve(Fixtures.load("player_ios.json"))
        assertTrue(info.url.startsWith("https://"))
        assertTrue("expected audio mime, was ${info.mimeType}", info.mimeType.startsWith("audio/"))
        assertTrue(info.bitrate > 0)
        // Highest-bitrate audio format in the fixture is itag 251 (opus).
        assertEquals(136544, info.bitrate)
        assertTrue(info.mimeType.startsWith("audio/webm"))
        assertEquals(3433755L, info.contentLength)
        assertEquals(213061L, info.approxDurationMs)
    }

    @Test
    fun unplayableFixtureThrowsPlayerRefusedWithServerReason() {
        try {
            StreamResolver.resolve(Fixtures.load("player_unplayable.json"))
            fail("expected PlayerRefused")
        } catch (e: PlayerRefused) {
            assertEquals("Video unavailable", e.reason)
            assertTrue(e.message!!.contains("Video unavailable"))
        }
    }

    @Test
    fun cipheredOnlyFixtureThrowsNoPlayableStream() {
        try {
            StreamResolver.resolve(Fixtures.load("player_ciphered.json"))
            fail("expected NoPlayableStream")
        } catch (e: NoPlayableStream) {
            assertTrue(e.message!!.contains("No playable stream"))
        }
    }

    @Test
    fun garbageThrowsParseFailure() {
        try {
            StreamResolver.resolve("this is not json")
            fail("expected ParseFailure")
        } catch (e: ParseFailure) {
            // expected
        }
    }
}
