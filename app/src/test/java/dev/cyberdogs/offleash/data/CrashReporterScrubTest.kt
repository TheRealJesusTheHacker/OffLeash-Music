package dev.cyberdogs.offleash.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crash-trace scrub (bug sweep round 2, charter 3): a stack trace
 * prints exception messages verbatim, and a message from the media
 * or network layer can embed the signed googlevideo URL it failed
 * on. The trace file is local-only, but it is exactly what the
 * owner is asked to screenshot when a crash is diagnosed — a signed
 * URL must not survive into it. Class names, thread names and
 * non-link message text stay (CrashReporterTest pins those).
 */
class CrashReporterScrubTest {

    @Test
    fun signedStreamUrlInAMessageIsRedacted() {
        val trace = CrashReporter.formatTrace(
            "main",
            RuntimeException(
                "could not fetch https://rr1---sn-x.googlevideo.com/videoplayback" +
                    "?expire=1792821330&sig=MARKERSECRET for the track",
            ),
            epochMs = 0L,
        )
        assertFalse(trace.contains("MARKERSECRET"))
        assertFalse(trace.contains("googlevideo.com"))
        assertFalse(trace.contains("expire="))
        assertTrue(trace.contains("[link removed]"))
        assertTrue(trace.contains("java.lang.RuntimeException"))
        assertTrue(trace.contains("thread: main"))
    }

    @Test
    fun signedUrlInACauseMessageIsRedactedToo() {
        val cause = IllegalStateException(
            "upstream https://example.com/play?token=CAUSEMARKER failed",
        )
        val trace = CrashReporter.formatTrace(
            "bg",
            RuntimeException("wrapper failure", cause),
            epochMs = 0L,
        )
        assertFalse(trace.contains("CAUSEMARKER"))
        assertFalse(trace.contains("example.com/play"))
        assertTrue(trace.contains("wrapper failure"))
        assertTrue(trace.contains("[link removed]"))
    }

    @Test
    fun plainMessagesPassThroughUntouched() {
        val trace = CrashReporter.formatTrace(
            "main",
            RuntimeException("boom-marker"),
            epochMs = 0L,
        )
        assertTrue(trace.contains("boom-marker"))
        assertFalse(trace.contains("[link removed]"))
    }
}
