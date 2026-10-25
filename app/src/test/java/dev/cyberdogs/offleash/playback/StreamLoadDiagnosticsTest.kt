package dev.cyberdogs.offleash.playback

import dev.cyberdogs.offleash.data.RangeInterceptor
import dev.cyberdogs.offleash.data.StreamFetchShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round 3 diagnostics visibility, pinned at the pure-formatter
 * level (the recorder's established test pattern): every stream
 * load of an attempt gets a numbered `load:` line with offset +
 * window + status; every probe window tried gets a `probe-window:`
 * line; the winner gets a `window:` line. These formatters are
 * what PlaybackDiagnostics.recordStreamLoad / recordProbeWindow /
 * recordWinningWindow emit, so the literal formats are pinned
 * here, not paraphrased.
 */
class StreamLoadDiagnosticsTest {

    @Test
    fun rangeOffsetAndWindowParsesBoundedRanges() {
        assertEquals(0L to 1048576L, rangeOffsetAndWindow("bytes=0-1048575"))
        assertEquals(1048576L to 262144L, rangeOffsetAndWindow("bytes=1048576-1310719"))
        assertEquals(5000L to 2048L, rangeOffsetAndWindow("bytes=5000-7047"))
        assertEquals(0L to 1L, rangeOffsetAndWindow("bytes=0-0"))
    }

    @Test
    fun rangeOffsetAndWindowRejectsUnboundedShapes() {
        assertNull(rangeOffsetAndWindow("none"))
        assertNull(rangeOffsetAndWindow("bytes=5000-"))
        assertNull(rangeOffsetAndWindow("bytes=9-5"))
        assertNull(rangeOffsetAndWindow("garbage"))
    }

    @Test
    fun streamLoadLineCarriesIndexOffsetWindowAndStatus() {
        val shape = StreamFetchShape(
            range = "bytes=1048576-1310719",
            userAgent = "okhttp-default",
            acceptEncoding = "absent",
        )
        assertEquals(
            "#3 HTTP 206 host=rr1---sn-example.googlevideo.com " +
                "offset=1048576 window=262144 range=bytes=1048576-1310719 " +
                "ua=okhttp-default ae=absent",
            streamLoadLine(3, 206, "rr1---sn-example.googlevideo.com", shape),
        )
        // A refused load is just as readable as a served one.
        val refused = StreamFetchShape("bytes=0-2047", "okhttp-default", "absent")
        assertEquals(
            "#1 HTTP 403 host=rr1---sn-example.googlevideo.com " +
                "offset=0 window=2048 range=bytes=0-2047 ua=okhttp-default ae=absent",
            streamLoadLine(1, 403, "rr1---sn-example.googlevideo.com", refused),
        )
    }

    @Test
    fun probeWindowLineNamesPairContextWindowAndAnswer() {
        assertEquals(
            "pair 1/3 context IOS window=1048576 range=bytes=0-1048575 HTTP 403",
            probeWindowLine(1, 3, "IOS", 1048576L, 403, null),
        )
        assertEquals(
            "pair 2/3 context ANDROID window=2048 range=bytes=0-2047 HTTP 206",
            probeWindowLine(2, 3, "ANDROID", 2048L, 206, null),
        )
        assertEquals(
            "pair 1/3 context IOS window=65536 range=bytes=0-65535 probe failed (IOException)",
            probeWindowLine(1, 3, "IOS", 65536L, null, "IOException"),
        )
    }

    @Test
    fun winningWindowLineNamesTheWindowAndItsRange() {
        assertEquals(
            "winner window=262144 range=bytes=0-262143 " +
                "— player loads of this stream use this window",
            winningWindowLine(262144L),
        )
        // The line's Range must be the shared definition's own
        // bounded header for that window — no second spelling.
        for (window in RangeInterceptor.STREAM_WINDOW_LADDER) {
            assertEquals(
                "winner window=$window range=${RangeInterceptor.boundedRangeHeader(0L, window)} " +
                    "— player loads of this stream use this window",
                winningWindowLine(window),
            )
        }
    }
}
