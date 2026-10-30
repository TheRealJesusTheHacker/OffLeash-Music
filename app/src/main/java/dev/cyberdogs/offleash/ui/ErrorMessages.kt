package dev.cyberdogs.offleash.ui

import dev.cyberdogs.offleash.core.error.AllRoadsRefused
import dev.cyberdogs.offleash.core.error.ContextRejected
import dev.cyberdogs.offleash.core.error.NetworkFailure
import dev.cyberdogs.offleash.core.error.NoPlayableStream
import dev.cyberdogs.offleash.core.error.OffleashException
import dev.cyberdogs.offleash.core.error.ParseFailure
import dev.cyberdogs.offleash.core.error.PlayerRefused

/**
 * The ONE place core errors become user copy — the SPEC §6 shapes.
 * Every screen, the queue controller and every error panel go through here.
 *
 * User copy is plain language only: it names what happened and what
 * to do next. Raw server wording, exception text, status tokens like
 * LOGIN_REQUIRED and Media3 error codes are diagnostics — they live
 * in the playback diagnostics recorder, never in a message the user
 * reads. (The owner's 2026-10-27 screenshot showed a card ending in
 * "(player error 2000)"; that fallback is why this rule is stated
 * here, at the one place the copy is made.)
 */
object ErrorMessages {

    fun userMessage(error: Throwable): String = when (error) {
        is AllRoadsRefused ->
            "YouTube refused this track both ways. " +
                "Signed in: ${plainReason(error.accountError)} " +
                "Anonymous: ${plainReason(error.anonymousError)}"
        is ContextRejected -> contextRejectedMessage(error)
        is PlayerRefused -> playerRefusedMessage(error.reason)
        is NoPlayableStream ->
            "No playable stream for this track — YouTube served only locked formats."
        is NetworkFailure ->
            "Couldn't reach YouTube Music — check your connection."
        is ParseFailure ->
            "YouTube Music answered in a shape this app doesn't understand yet — " +
                "the app may need an update."
        else ->
            "Something went wrong. Try again."
    }

    /**
     * A refusal, named by its class (0.8.2): the structured fields
     * on [ContextRejected] say WHICH refusal happened, and each
     * class gets the copy (and the next step) that fits it, instead
     * of one generic line for all of them. A refusal carrying no
     * fields — every construction predating the structured parse —
     * keeps the original line verbatim.
     *
     * - 401 / UNAUTHENTICATED: the sign-in itself was refused — the
     *   fix the user can actually take is signing in again.
     * - 403 / PERMISSION_DENIED: the account was refused this
     *   request — signing in again won't change it; say so plainly.
     * - 400 / FAILED_PRECONDITION and everything else: the request
     *   shape was refused — the app's side of the contract, the
     *   original "may need an update" line.
     */
    private fun contextRejectedMessage(error: ContextRejected): String = when {
        error.httpStatus == 401 || error.serverStatus == "UNAUTHENTICATED" ->
            "YouTube refused the app's sign-in for this — sign out and back in, then try again."
        error.httpStatus == 403 || error.serverStatus == "PERMISSION_DENIED" ->
            "YouTube refused this request for your account."
        else ->
            "YouTube refused the app's request — the app may need an update."
    }

    /**
     * One road's refusal, in plain language, for the both-roads
     * message. Deliberately built from the failure's TYPE (plus the
     * one playability token that changes what the user should do,
     * below) — never from the exception's own message, which carries
     * raw server wording.
     */
    private fun plainReason(error: OffleashException): String = when (error) {
        is ContextRejected -> "YouTube refused the app's request."
        is PlayerRefused -> playerRefusedMessage(error.reason)
        is NoPlayableStream -> "no playable stream was served."
        is NetworkFailure -> "the connection failed."
        is ParseFailure -> "YouTube answered in a shape the app doesn't understand."
        is AllRoadsRefused -> "refused both ways."
    }

    /**
     * A playability refusal, in plain language. YouTube's reason is a
     * machine token (LOGIN_REQUIRED, UNPLAYABLE, …): the one token
     * that changes the user's next step is named in plain words, and
     * every other token becomes the same honest sentence — the token
     * itself is never shown.
     */
    private fun playerRefusedMessage(reason: String): String =
        if (reason.contains("LOGIN_REQUIRED", ignoreCase = true)) {
            "YouTube won't play this track without a sign-in."
        } else {
            "YouTube won't play this track."
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
     * An unrecognised code gets plain copy and the diagnostics
     * pointer — the code itself is recorded in playback diagnostics
     * (QueueController calls `recordPlayerError`), so a screenshot
     * of About → Playback diagnostics stays actionable without the
     * card the user reads carrying a raw code.
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
            "Something went wrong playing this track. Try again; if it keeps " +
                "happening, open About → Playback diagnostics and send a screenshot."
    }
}
