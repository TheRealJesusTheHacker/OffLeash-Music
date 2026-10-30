package dev.cyberdogs.offleash.playback

/**
 * Player-error recovery decisions (H7, 2026-10-27 full bug sweep).
 *
 * The owner's screenshot: a track playing at 0:42, then "Something
 * went wrong playing this track (player error 2000)" and a dead
 * stop. Error 2000 is Media3's ERROR_CODE_IO_UNSPECIFIED — an
 * IO-class failure with no HTTP status at all, e.g. the connection
 * dropped mid-stream. The shipped onPlayerError only recovered from
 * an HTTP 403 (found in the cause chain); every other player error
 * published the card and stopped, even though a fresh resolve (a new
 * signed URL, the resolver advancing roads) is exactly the cure for
 * a transient mid-stream drop.
 *
 * This file is the pure decision the controller wires in: given the
 * error's class, the HTTP status when there is one, whether the
 * failing item is a local/downloaded file, how much recovery budget
 * is left, and whether the queue has a next track, it names the ONE
 * action to take. Pure so the whole matrix is unit-testable without
 * a player; QueueController keeps the side effects.
 *
 * The error-code ranges mirror androidx.media3.common
 * .PlaybackException in Media3 1.5.1: 2000..2999 is the IO family
 * (ERROR_CODE_IO_UNSPECIFIED 2000, NETWORK_CONNECTION_FAILED 2001,
 * NETWORK_CONNECTION_TIMEOUT 2002, INVALID_HTTP_CONTENT_TYPE 2003,
 * BAD_HTTP_STATUS 2004, FILE_NOT_FOUND 2005, NO_PERMISSION 2006,
 * CLEARTEXT_NOT_PERMITTED 2007, READ_POSITION_OUT_OF_RANGE 2008).
 * Parsing (3xxx), decoder (4xxx) and DRM (6xxx) failures are NOT
 * cured by a fresh stream URL, so they never enter recovery.
 */

/**
 * How many automatic re-resolves one track gets for IO-class player
 * errors before the controller gives up on it and moves on. Three
 * matches the fleet's other bounded retries (the probe ladder's
 * pairs, autoplay's consecutive-failure cap): the first attempt
 * heals the plain transient drop from the H7 report, the second
 * covers a network handover (Wi-Fi to cellular) landing mid-retry,
 * and the third covers one bad re-resolve among good ones. Past
 * that, the track or the road is broken, not unlucky — skipping is
 * kinder than hammering the resolver, and the error still surfaces
 * when there is nowhere to skip to.
 */
const val MAX_STREAM_RECOVERY_ATTEMPTS: Int = 3

/** True when [errorCode] is in Media3's IO family (2000..2999). */
fun isIoPlayerError(errorCode: Int): Boolean = errorCode in 2000..2999

/** What QueueController does with one player error. */
enum class PlayerErrorRecoveryAction {
    /**
     * The shipped 403 fast path: the cause chain carried an HTTP
     * 403 and this track PLAY has not used its one refused-stream
     * re-resolve yet. Does not consume the IO recovery budget —
     * but the one-shot is per track play, not per READY segment:
     * the controller does not refund it on STATE_READY (bug sweep
     * round 3), or a stream that keeps dying mid-stream with 403s
     * would re-enter this path forever, never spending the IO
     * budget and never reaching the skip/surface below.
     */
    RE_RESOLVE_REFUSED,

    /**
     * IO-class failure (dropped connection, timeout, expired or
     * refused stream): re-resolve the same track fresh and resume
     * at the position captured when the error fired. Consumes one
     * unit of the [MAX_STREAM_RECOVERY_ATTEMPTS] budget.
     */
    RE_RESOLVE_IO,

    /**
     * The failing item is a downloaded/local file: stream the same
     * track from the network instead, once. A local file can be
     * truncated or corrupt in ways the download record cannot see
     * (existence + non-zero length is all the offline check can
     * prove), so a player error on one is not the end of the track.
     */
    FALLBACK_TO_STREAM,

    /**
     * The IO recovery budget is spent and the queue has a next
     * track under the current repeat/shuffle semantics: advance
     * instead of parking the listener on a dead track.
     */
    SKIP_TO_NEXT,

    /**
     * Nothing left to try: publish the named error and stop, the
     * SPEC §4 behavior. The error card's Try again re-resolves from
     * scratch with fresh budgets.
     */
    SURFACE_ERROR,
}

/**
 * The recovery decision for one player error.
 *
 * Order is deliberate:
 * 1. A LOCAL failure never re-resolves (there is no URL to
 *    refresh) and never skips first — the same track streamed is
 *    the honest fallback, once; a failure after that fallback is a
 *    streamed failure and re-enters this decision as one (the
 *    controller clears its local marker at the stream hand-off).
 * 2. The 403 fast path keeps its shipped shape — one re-resolve,
 *    gated by [refusedRetryUsed] exactly like
 *    [shouldRetryAfterPlayerError] — and runs before the IO budget
 *    so a 403 never spends it. The gate is per track play: once it
 *    is spent, later 403s on the same play are ordinary IO
 *    failures under step 3, however many READYs intervened
 *    (bug sweep round 3 — see the controller's budget note).
 * 3. IO-class errors re-resolve while [ioRecoveryAttempts] remains,
 *    then skip when [hasNextTrack], else surface.
 * 4. Everything else (parsing, decoder, DRM, unknown) surfaces:
 *    a fresh URL does not fix a file the device cannot decode.
 *
 * The skip in step 3 is additionally bounded by the CASCADE cap
 * (chaos sweep round 2): [cascadeSkips] counts the recovery skips
 * already taken since the last successful READY, and a skip is
 * only offered while fewer than `queueSize - 1` skips have been
 * taken. Under repeat ALL the queue's peek/next wrap forever, so
 * without this cap a queue in which EVERY track IO-fails cycled
 * endlessly — each wrap re-armed every track's budget (a fresh
 * play resets it) and the error never surfaced: an unbounded
 * resolver/battery burn on a dead road. With the cap, one cascade
 * tries each track at most once and then surfaces, exactly like a
 * finite queue does. A track that reaches READY ends the cascade
 * (the controller resets the counter there), so isolated broken
 * tracks in a long queue still skip one by one as before.
 */
fun playerErrorRecoveryDecision(
    errorCode: Int,
    httpStatus: Int?,
    isLocalPlayback: Boolean,
    localFallbackUsed: Boolean,
    refusedRetryUsed: Boolean,
    ioRecoveryAttempts: Int,
    hasNextTrack: Boolean,
    cascadeSkips: Int = 0,
    queueSize: Int = Int.MAX_VALUE,
): PlayerErrorRecoveryAction = when {
    isLocalPlayback ->
        if (localFallbackUsed) {
            PlayerErrorRecoveryAction.SURFACE_ERROR
        } else {
            PlayerErrorRecoveryAction.FALLBACK_TO_STREAM
        }
    httpStatus == 403 && !refusedRetryUsed ->
        PlayerErrorRecoveryAction.RE_RESOLVE_REFUSED
    isIoPlayerError(errorCode) && ioRecoveryAttempts < MAX_STREAM_RECOVERY_ATTEMPTS ->
        PlayerErrorRecoveryAction.RE_RESOLVE_IO
    isIoPlayerError(errorCode) && hasNextTrack && cascadeSkips < queueSize - 1 ->
        PlayerErrorRecoveryAction.SKIP_TO_NEXT
    else -> PlayerErrorRecoveryAction.SURFACE_ERROR
}

/**
 * True when a player error is STALE and must be ignored (chaos
 * sweep round 2): a resolve is already in flight, which means the
 * item in the player is being replaced — by a recovery re-resolve,
 * a skip, or a fresh user play. An error arriving in that window
 * belongs to the outgoing item, while the controller's queue
 * position already names its successor: deciding on it would spend
 * the SUCCESSOR track's budgets (or skip it) for a failure it never
 * had, and its recovery re-resolve would cancel the in-flight one.
 * The in-flight resolve owns the player's next state; if the new
 * item fails too, its own error arrives with no resolve in flight.
 */
fun playerErrorIsStale(resolveInFlight: Boolean): Boolean = resolveInFlight
