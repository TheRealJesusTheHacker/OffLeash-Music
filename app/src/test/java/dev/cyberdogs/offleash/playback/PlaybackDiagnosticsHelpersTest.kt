package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackDiagnosticsHelpersTest {

    @Test
    fun ipv6EmbeddedIpReportsFamilyOnly() {
        assertEquals(
            "IPv6",
            embeddedIpFamily("https://rr1---sn.googlevideo.com/videoplayback?ip=2001:db8::1&expire=1"),
        )
    }

    @Test
    fun ipv4EmbeddedIpReportsIpv4() {
        assertEquals(
            "IPv4",
            embeddedIpFamily("https://rr1---sn.googlevideo.com/videoplayback?expire=1&ip=203.0.113.7"),
        )
    }

    @Test
    fun missingEmbeddedIpReportsNone() {
        assertEquals("none", embeddedIpFamily("https://rr1---sn.googlevideo.com/videoplayback?expire=1"))
    }

    @Test
    fun hostIsParsed() {
        assertEquals(
            "rr1---sn.googlevideo.com",
            streamHost("https://rr1---sn.googlevideo.com/videoplayback?x=1"),
        )
    }

    @Test
    fun malformedEmbeddedIpEscapeReportsNone() {
        // URLDecoder throws on a malformed escape; the helper is
        // total (0.8.7 sweep 1) — an unreadable ip parameter is
        // reported exactly like an absent one, never thrown into
        // the resolve path that records it.
        assertEquals(
            "none",
            embeddedIpFamily(
                "https://rr1---sn.googlevideo.com/videoplayback?ip=%zz&expire=1",
            ),
        )
        assertEquals(
            "none",
            embeddedIpFamily(
                "https://rr1---sn.googlevideo.com/videoplayback?ip=%&expire=1",
            ),
        )
    }
}
