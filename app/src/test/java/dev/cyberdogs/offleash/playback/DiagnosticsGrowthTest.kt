package dev.cyberdogs.offleash.playback

import dev.cyberdogs.offleash.data.StreamFetchShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug sweep round 2 (lane C3): PlaybackDiagnostics growth bound.
 *
 * The recorder lives for the whole process and the shared stack's
 * interceptor logs EVERY stream load (plus every ladder probe
 * window and service lifecycle event). Before round 2 the line
 * list was unbounded and every append copied it whole into the
 * snapshot — a long, seek-heavy session grew memory and per-load
 * cost for the process lifetime. The newest [MAX_LOG_LINES] lines
 * (the current attempt's story) are kept; the oldest drop.
 */
class DiagnosticsGrowthTest {

    @Test
    fun logLinesAreBoundedWithTheNewestKept() {
        val diagnostics = PlaybackDiagnostics()
        repeat(1000) { diagnostics.log("load", "load number $it") }
        val lines = diagnostics.lines()
        assertEquals(PlaybackDiagnostics.MAX_LOG_LINES, lines.size)
        assertTrue(lines.last().contains("load number 999"))
        assertTrue(lines.none { it.contains("load number 0 ") || it.endsWith("load number 0") })
        assertEquals(lines.size, diagnostics.snapshot.value.size)
    }

    @Test
    fun streamLoadCountStaysExactWhileLinesStayBounded() {
        val diagnostics = PlaybackDiagnostics()
        diagnostics.startAttempt("track")
        val shape = StreamFetchShape(
            range = "bytes=0-1048575",
            userAgent = "okhttp-default",
            acceptEncoding = "absent",
        )
        repeat(500) { diagnostics.recordStreamLoad(206, "rr1.googlevideo.com", shape) }
        // The per-attempt load COUNT is a named field, not a line:
        // it stays exact even though the lines ring.
        assertEquals(500, diagnostics.streamLoadCount)
        assertEquals(206, diagnostics.firstFetchStatus)
        assertTrue(diagnostics.lines().size <= PlaybackDiagnostics.MAX_LOG_LINES)
    }

    @Test
    fun concurrentAppendsStayBoundedAndTheLoadCountStaysExact() {
        // Bug sweep round 3 attack on the round-2 ring: the
        // interceptor calls in from many OkHttp threads at once.
        // The ring's bound, the snapshot's agreement with the line
        // list, and the per-attempt load counter (a field, mutated
        // under the same lock) must all survive the hammering —
        // the round-2 code kept the old code's synchronization, so
        // this pins that it stays kept.
        val diagnostics = PlaybackDiagnostics()
        diagnostics.startAttempt("track")
        val shape = StreamFetchShape(
            range = "bytes=0-2047",
            userAgent = "okhttp-default",
            acceptEncoding = "absent",
        )
        val threads = (1..8).map { t ->
            Thread {
                repeat(250) { i ->
                    diagnostics.recordStreamLoad(206, "rr$t.googlevideo.com", shape)
                    diagnostics.log("probe-window", "thread $t window $i")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(8 * 250, diagnostics.streamLoadCount)
        assertEquals(206, diagnostics.firstFetchStatus)
        val lines = diagnostics.lines()
        assertTrue(lines.size <= PlaybackDiagnostics.MAX_LOG_LINES)
        assertEquals(lines.size, diagnostics.snapshot.value.size)
        assertEquals(lines, diagnostics.snapshot.value)
    }

    @Test
    fun signInDiagnosticsRingIsBoundedUnderConcurrentAppends() {
        // The sign-in recorder has the same ring and the same
        // multi-thread callers (poll loop, refresh, sign-out).
        val diagnostics = dev.cyberdogs.offleash.data.SignInDiagnostics()
        val threads = (1..4).map { t ->
            Thread {
                repeat(250) { i -> diagnostics.log("poll", "thread $t poll $i") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val lines = diagnostics.lines()
        assertEquals(
            dev.cyberdogs.offleash.data.SignInDiagnostics.MAX_LOG_LINES,
            lines.size,
        )
        assertEquals(lines, diagnostics.snapshot.value)
    }

    @Test
    fun startAttemptStillResetsTheBoundedLog() {
        val diagnostics = PlaybackDiagnostics()
        repeat(500) { diagnostics.log("probe-window", "window $it") }
        diagnostics.startAttempt("fresh track")
        val lines = diagnostics.lines()
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("attempt: fresh track"))
    }
}
