package dev.cyberdogs.offleash.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerMessageTest {

    // Media3 1.5.1 PlaybackException codes (ints, as QueueController passes).
    private companion object {
        const val IO_NETWORK_CONNECTION_FAILED = 2001
        const val IO_NETWORK_CONNECTION_TIMEOUT = 2002
        const val IO_INVALID_HTTP_CONTENT_TYPE = 2003
        const val IO_BAD_HTTP_STATUS = 2004
        const val IO_READ_POSITION_OUT_OF_RANGE = 2008
        const val PARSING_CONTAINER_UNSUPPORTED = 3003
        const val PARSING_MANIFEST_UNSUPPORTED = 3004
        const val DECODER_INIT_FAILED = 4001
        const val DECODING_FAILED = 4003
    }

    @Test
    fun badHttpStatus403NamesRefusalAndDiagnostics() {
        val msg = ErrorMessages.playerMessage(IO_BAD_HTTP_STATUS, 403)
        assertEquals(
            "YouTube refused this track's stream (HTTP 403) — the stream link was " +
                "likely issued for a different network address or has expired. " +
                "Try again; if it keeps happening, open About → Playback " +
                "diagnostics and send a screenshot.",
            msg,
        )
    }

    @Test
    fun badHttpStatus404NamesExpiredLink() {
        val msg = ErrorMessages.playerMessage(IO_BAD_HTTP_STATUS, 404)
        assertEquals(
            "YouTube says this track's stream is gone (HTTP 404) — the link " +
                "expired. Try again to fetch a fresh one.",
            msg,
        )
    }

    @Test
    fun badHttpStatusOtherIncludesStatus() {
        assertEquals(
            "YouTube's stream server answered HTTP 500. Try again.",
            ErrorMessages.playerMessage(IO_BAD_HTTP_STATUS, 500),
        )
    }

    @Test
    fun networkCodesShareConnectionCopy() {
        val expected = "Couldn't load this track's audio — the connection failed. " +
            "Check your connection and try again."
        for (code in listOf(
            IO_NETWORK_CONNECTION_FAILED,
            IO_NETWORK_CONNECTION_TIMEOUT,
            IO_INVALID_HTTP_CONTENT_TYPE,
            IO_READ_POSITION_OUT_OF_RANGE,
        )) {
            assertEquals(expected, ErrorMessages.playerMessage(code, null))
        }
    }

    @Test
    fun decoderAndParsingCodesShareFormatCopy() {
        val expected = "This track's audio format can't be played on this device."
        for (code in listOf(
            PARSING_CONTAINER_UNSUPPORTED,
            PARSING_MANIFEST_UNSUPPORTED,
            DECODER_INIT_FAILED,
            DECODING_FAILED,
        )) {
            assertEquals(expected, ErrorMessages.playerMessage(code, null))
        }
    }

    @Test
    fun fallbackCarriesNoRawCode() {
        // The owner's 2026-10-27 screenshot: a user-facing card that
        // ended "(player error 2000)". The code is diagnostics (it
        // is recorded by recordPlayerError); user copy points at the
        // diagnostics screen instead of printing the code.
        val msg = ErrorMessages.playerMessage(9999, null)
        assertEquals(
            "Something went wrong playing this track. Try again; if it keeps " +
                "happening, open About → Playback diagnostics and send a screenshot.",
            msg,
        )
        assertTrue(!msg.contains("9999"))
    }

    @Test
    fun userMessageNeverLeaksRawServerWording() {
        // Server wording, playability tokens and exception text are
        // diagnostics, not user copy: none of these raw strings may
        // reach the rendered message.
        val rejected = ErrorMessages.userMessage(
            dev.cyberdogs.offleash.core.error.ContextRejected("RAW-JSON-{\"error\":400}"),
        )
        assertTrue(!rejected.contains("RAW-JSON"))
        val refused = ErrorMessages.userMessage(
            dev.cyberdogs.offleash.core.error.PlayerRefused("UNPLAYABLE-RAW-TOKEN"),
        )
        assertTrue(!refused.contains("UNPLAYABLE-RAW-TOKEN"))
        assertEquals("YouTube won't play this track.", refused)
        val bothRoads = ErrorMessages.userMessage(
            dev.cyberdogs.offleash.core.error.AllRoadsRefused(
                accountError = dev.cyberdogs.offleash.core.error.PlayerRefused("LOGIN_REQUIRED"),
                anonymousError = dev.cyberdogs.offleash.core.error.NetworkFailure("socket-raw-detail"),
            ),
        )
        assertTrue(!bothRoads.contains("LOGIN_REQUIRED"))
        assertTrue(!bothRoads.contains("socket-raw-detail"))
        assertTrue(bothRoads.contains("without a sign-in"))
        assertTrue(bothRoads.contains("the connection failed"))
    }
}
