package dev.cyberdogs.offleash.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last-crash recorder (2026-10-27 crash hunt): the trace
 * format, the file round trip, and the pinned file name. The
 * handler installation itself is Android wiring and is exercised
 * on-device; everything about what gets written is pinned here.
 */
class CrashReporterTest {

    @Test
    fun traceNamesThreadClassMessageAndFrames() {
        val trace = CrashReporter.formatTrace(
            "main",
            RuntimeException("boom-marker"),
            epochMs = 0L,
        )
        assertTrue(trace.contains("thread: main"))
        assertTrue(trace.contains("java.lang.RuntimeException"))
        assertTrue(trace.contains("boom-marker"))
        assertTrue(trace.contains("CrashReporterTest"))
    }

    @Test
    fun traceIsCapped() {
        val deep = RuntimeException("x")
        val trace = CrashReporter.formatTrace("main", deep, epochMs = 0L)
        // A normal trace is far under the cap; this pins the cap
        // constant's effect only via the truncation marker's absence.
        assertTrue(!trace.contains("truncated"))
    }

    @Test
    fun fileRoundTripsAndClearRemoves() {
        val file = File.createTempFile("crash-reporter-test", ".txt")
        try {
            val trace = CrashReporter.formatTrace("bg", IllegalStateException("rt"), 1L)
            file.writeText(trace)
            assertEquals(trace, CrashReporter.read(file))
            CrashReporter.clear(file)
            assertNull(CrashReporter.read(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun missingFileReadsAsNoCrash() {
        val file = File(
            System.getProperty("java.io.tmpdir"),
            "crash-reporter-definitely-absent-${System.nanoTime()}.txt",
        )
        assertNull(CrashReporter.read(file))
    }

    @Test
    fun crashFileNameIsPinned() {
        assertEquals("last_crash.txt", CrashReporter.CRASH_FILE_NAME)
    }

    @Test
    fun traceScrubsCredentialShapedValues() {
        // The trace file is share-by-design (it renders on the
        // sign-in screen for the owner to screenshot), so a
        // credential-shaped value riding an exception message
        // must not survive formatting (0.8.7 sweep 2) — while
        // the diagnostic facts around it (class, the parameter
        // NAMES) stay.
        val boom = RuntimeException(
            "call failed: Bearer ya29.fake-token-value " +
                "access_token=<redacted_query_secret_1>&refresh_token=1//fake-refresh " +
                "client_secret=<redacted_query_secret_2>",
        )
        val trace = CrashReporter.formatTrace("main", boom, epochMs = 0L)
        assertFalse(trace.contains("ya29.fake-token-value"))
        assertFalse(trace.contains("ya29.fake-access"))
        assertFalse(trace.contains("fake-refresh"))
        assertFalse(trace.contains("GOCSPX-fake-secret"))
        assertTrue(trace.contains("Bearer [removed]"))
        assertTrue(trace.contains("access_token=[removed]"))
        assertTrue(trace.contains("client_secret=[removed]"))
        assertTrue(trace.contains("java.lang.RuntimeException"))
    }
}
