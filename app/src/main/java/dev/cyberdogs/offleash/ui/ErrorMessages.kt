package dev.cyberdogs.offleash.ui

import dev.cyberdogs.offleash.core.error.AllRoadsRefused
import dev.cyberdogs.offleash.core.error.ContextRejected
import dev.cyberdogs.offleash.core.error.NetworkFailure
import dev.cyberdogs.offleash.core.error.NoPlayableStream
import dev.cyberdogs.offleash.core.error.ParseFailure
import dev.cyberdogs.offleash.core.error.PlayerRefused

/**
 * The ONE place core errors become user copy — the SPEC §6 shapes.
 * Every screen, the queue controller and every error panel go through here.
 */
object ErrorMessages {

    fun userMessage(error: Throwable): String = when (error) {
        is AllRoadsRefused ->
            "YouTube refused this track on both paths. " +
                "Signed-in path: ${error.accountError.message ?: "refused"} " +
                "Anonymous path: ${error.anonymousError.message ?: "refused"}"
        is ContextRejected ->
            "YouTube refused the app's request — the API has likely changed. " +
                "(server said: ${error.serverMessage})"
        is PlayerRefused ->
            "YouTube won't play this track: ${error.reason}"
        is NoPlayableStream ->
            "No playable stream for this track — YouTube served only locked formats."
        is NetworkFailure ->
            "Couldn't reach YouTube Music — check your connection."
        is ParseFailure ->
            "YouTube Music answered in a shape this app doesn't understand yet — " +
                "the API has likely changed."
        else ->
            "Something went wrong. Try again."
    }

    // Media3 PlaybackException error codes, mirrored as ints so this
    // function stays pure and JVM-testable (callers pass errorCode and
    // the HTTP status extracted from the cause chain, never the
    // exception itself). Values match androidx.media3.common
    // .PlaybackException in Media3 1.5.1.
    const val ERR_IO_NETWORK_CONNECTION_FAILED: Int = 2001
    const val ERR_IO_NETWORK_CONNECTION_TIMEOUT: Int = 2002
    const val ERR_IO_INVALID_HTTP_CONTENT_TYPE: Int = 2003
    const val ERR_IO_BAD_HTTP_STATUS: Int = 2004
    const val ERR_IO_READ_POSITION_OUT_OF_RANGE: Int = 2008
    const val ERR_PARSING_CONTAINER_UNSUPPORTED: Int = 3003
    const val ERR_PARSING_MANIFEST_UNSUPPORTED: Int = 3004
    const val ERR_DECODER_INIT_FAILED: Int = 4001
    const val ERR_DECODING_FAILED: Int = 4003

    /**
     * Named copy for a player-stage failure (SPEC §6, player rows).
     * There is deliberately no bare catch-all here: an unrecognised
     * code still names the code so a screenshot is actionable.
     */
    fun playerMessage(errorCode: Int, httpStatus: Int?): String = when {
        errorCode == ERR_IO_BAD_HTTP_STATUS && httpStatus == 403 ->
            "YouTube refused this track's stream (HTTP 403) — the stream link was " +
                "likely issued for a different network address or has expired. " +
                "Try again; if it keeps happening, open About → Playback " +
                "diagnostics and send a screenshot."
        errorCode == ERR_IO_BAD_HTTP_STATUS && httpStatus == 404 ->
            "YouTube says this track's stream is gone (HTTP 404) — the link " +
                "expired. Try again to fetch a fresh one."
        errorCode == ERR_IO_BAD_HTTP_STATUS ->
            "YouTube's stream server answered HTTP ${httpStatus ?: "unknown"}. Try again."
        errorCode == ERR_IO_NETWORK_CONNECTION_FAILED ||
            errorCode == ERR_IO_NETWORK_CONNECTION_TIMEOUT ||
            errorCode == ERR_IO_INVALID_HTTP_CONTENT_TYPE ||
            errorCode == ERR_IO_READ_POSITION_OUT_OF_RANGE ->
            "Couldn't load this track's audio — the connection failed. " +
                "Check your connection and try again."
        errorCode == ERR_PARSING_CONTAINER_UNSUPPORTED ||
            errorCode == ERR_PARSING_MANIFEST_UNSUPPORTED ||
            errorCode == ERR_DECODER_INIT_FAILED ||
            errorCode == ERR_DECODING_FAILED ->
            "This track's audio format can't be played on this device."
        else ->
            "Something went wrong playing this track (player error $errorCode). Try again."
    }
}
