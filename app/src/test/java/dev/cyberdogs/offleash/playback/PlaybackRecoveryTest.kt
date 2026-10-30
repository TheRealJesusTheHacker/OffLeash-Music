package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The H7 recovery matrix: what one player error does, decided
 * purely. The owner's defect — error 2000 mid-stream, no HTTP
 * status, dead stop — is the first case pinned; the shipped 403
 * fast path and the non-IO surface behavior are pinned alongside so
 * the recovery cannot quietly change them.
 */
class PlaybackRecoveryTest {

    private fun decide(
        errorCode: Int,
        httpStatus: Int? = null,
        isLocalPlayback: Boolean = false,
        localFallbackUsed: Boolean = false,
        refusedRetryUsed: Boolean = false,
        ioRecoveryAttempts: Int = 0,
        hasNextTrack: Boolean = false,
        cascadeSkips: Int = 0,
        queueSize: Int = Int.MAX_VALUE,
    ): PlayerErrorRecoveryAction = playerErrorRecoveryDecision(
        errorCode = errorCode,
        httpStatus = httpStatus,
        isLocalPlayback = isLocalPlayback,
        localFallbackUsed = localFallbackUsed,
        refusedRetryUsed = refusedRetryUsed,
        ioRecoveryAttempts = ioRecoveryAttempts,
        hasNextTrack = hasNextTrack,
        cascadeSkips = cascadeSkips,
        queueSize = queueSize,
    )

    // -- The H7 defect itself -------------------------------------------

    @Test
    fun error2000MidStreamReResolvesInsteadOfStopping() {
        // ERROR_CODE_IO_UNSPECIFIED, no HTTP status in the chain:
        // exactly the owner's screenshot (playing at 0:42, then
        // error 2000 and a dead stop on the shipped code).
        assertEquals(
            PlayerErrorRecoveryAction.RE_RESOLVE_IO,
            decide(errorCode = 2000, httpStatus = null),
        )
    }

    @Test
    fun ioClassBoundariesAre2000Through2999() {
        assertFalse(isIoPlayerError(1999))
        assertTrue(isIoPlayerError(2000))
        assertTrue(isIoPlayerError(2001)) // network connection failed
        assertTrue(isIoPlayerError(2002)) // network connection timeout
        assertTrue(isIoPlayerError(2004)) // bad HTTP status
        assertTrue(isIoPlayerError(2008)) // read position out of range
        assertTrue(isIoPlayerError(2999))
        assertFalse(isIoPlayerError(3000))
        assertFalse(isIoPlayerError(3003)) // parsing: container unsupported
        assertFalse(isIoPlayerError(4003)) // decoding failed
        assertFalse(isIoPlayerError(6000)) // DRM family
    }

    @Test
    fun everyIoCodeReResolvesWhileBudgetRemains() {
        for (code in listOf(2000, 2001, 2002, 2003, 2005, 2008)) {
            assertEquals(
                "error $code",
                PlayerErrorRecoveryAction.RE_RESOLVE_IO,
                decide(errorCode = code, ioRecoveryAttempts = 0),
            )
        }
    }

    @Test
    fun repeatedMidStreamFailuresSpendTheBudgetThenSkip() {
        // A track that reaches READY and then dies mid-stream again
        // and again: READY must not refund the budget. Attempts
        // 1..3 re-resolve; the fourth failure skips to the next
        // track when there is one.
        assertEquals(3, MAX_STREAM_RECOVERY_ATTEMPTS)
        assertEquals(
            PlayerErrorRecoveryAction.RE_RESOLVE_IO,
            decide(errorCode = 2000, ioRecoveryAttempts = 0, hasNextTrack = true),
        )
        assertEquals(
            PlayerErrorRecoveryAction.RE_RESOLVE_IO,
            decide(errorCode = 2000, ioRecoveryAttempts = 1, hasNextTrack = true),
        )
        assertEquals(
            PlayerErrorRecoveryAction.RE_RESOLVE_IO,
            decide(errorCode = 2000, ioRecoveryAttempts = 2, hasNextTrack = true),
        )
        assertEquals(
            PlayerErrorRecoveryAction.SKIP_TO_NEXT,
            decide(errorCode = 2000, ioRecoveryAttempts = 3, hasNextTrack = true),
        )
        assertEquals(
            PlayerErrorRecoveryAction.SKIP_TO_NEXT,
            decide(errorCode = 2001, ioRecoveryAttempts = 99, hasNextTrack = true),
        )
    }

    @Test
    fun exhaustedBudgetWithNoNextTrackSurfacesTheError() {
        assertEquals(
            PlayerErrorRecoveryAction.SURFACE_ERROR,
            decide(errorCode = 2000, ioRecoveryAttempts = 3, hasNextTrack = false),
        )
    }

    // -- The shipped 403 fast path stays compatible ----------------------

    @Test
    fun first403UsesTheRefusedFastPathAndNotTheIoBudget() {
        assertEquals(
            PlayerErrorRecoveryAction.RE_RESOLVE_REFUSED,
            decide(errorCode = 2004, httpStatus = 403, refusedRetryUsed = false),
        )
        // The fast path is checked before the budget, so it wins
        // even when the IO budget is already spent.
        assertEquals(
            PlayerErrorRecoveryAction.RE_RESOLVE_REFUSED,
            decide(
                errorCode = 2004,
                httpStatus = 403,
                refusedRetryUsed = false,
                ioRecoveryAttempts = MAX_STREAM_RECOVERY_ATTEMPTS,
            ),
        )
    }

    @Test
    fun second403FallsIntoTheIoBudget() {
        // The one refused re-resolve is spent; further 403s are
        // ordinary IO failures with the ordinary budget and skip.
        assertEquals(
            PlayerErrorRecoveryAction.RE_RESOLVE_IO,
            decide(errorCode = 2004, httpStatus = 403, refusedRetryUsed = true),
        )
        assertEquals(
            PlayerErrorRecoveryAction.SKIP_TO_NEXT,
            decide(
                errorCode = 2004,
                httpStatus = 403,
                refusedRetryUsed = true,
                ioRecoveryAttempts = MAX_STREAM_RECOVERY_ATTEMPTS,
                hasNextTrack = true,
            ),
        )
        assertEquals(
            PlayerErrorRecoveryAction.SURFACE_ERROR,
            decide(
                errorCode = 2004,
                httpStatus = 403,
                refusedRetryUsed = true,
                ioRecoveryAttempts = MAX_STREAM_RECOVERY_ATTEMPTS,
                hasNextTrack = false,
            ),
        )
    }

    @Test
    fun alternating403AndIoFailuresAreBoundedInTotalPerTrackPlay() {
        // Bug sweep round 3 — the composition that broke the round-1
        // fix: the 403 flag used to be refunded by every STATE_READY
        // while the IO budget was not, so a track whose stream kept
        // dying mid-stream specifically with 403s alternated
        // READY -> 403 -> RE_RESOLVE_REFUSED forever, never spending
        // the IO budget and never reaching the skip. With the flag
        // on the same per-track-play lifecycle as the IO budget
        // (the controller's accounting, pinned by contract test),
        // one play's failures go: one refused re-resolve, then the
        // ordinary IO budget, then the skip/surface — in ANY order.
        assertEquals(
            PlayerErrorRecoveryAction.RE_RESOLVE_REFUSED,
            decide(errorCode = 2004, httpStatus = 403, refusedRetryUsed = false),
        )
        // The refused one-shot is spent for this play; a later 403
        // (after any number of READYs) is an ordinary IO failure.
        assertEquals(
            PlayerErrorRecoveryAction.RE_RESOLVE_IO,
            decide(
                errorCode = 2004, httpStatus = 403,
                refusedRetryUsed = true, ioRecoveryAttempts = 0,
            ),
        )
        assertEquals(
            PlayerErrorRecoveryAction.RE_RESOLVE_IO,
            decide(
                errorCode = 2000, httpStatus = null,
                refusedRetryUsed = true, ioRecoveryAttempts = 1,
            ),
        )
        assertEquals(
            PlayerErrorRecoveryAction.RE_RESOLVE_IO,
            decide(
                errorCode = 2004, httpStatus = 403,
                refusedRetryUsed = true, ioRecoveryAttempts = 2,
            ),
        )
        // Total spent: 1 refused + 3 IO. The next failure of either
        // kind skips (or surfaces) instead of re-resolving again.
        assertEquals(
            PlayerErrorRecoveryAction.SKIP_TO_NEXT,
            decide(
                errorCode = 2004, httpStatus = 403,
                refusedRetryUsed = true,
                ioRecoveryAttempts = MAX_STREAM_RECOVERY_ATTEMPTS,
                hasNextTrack = true,
            ),
        )
        assertEquals(
            PlayerErrorRecoveryAction.SURFACE_ERROR,
            decide(
                errorCode = 2000, httpStatus = null,
                refusedRetryUsed = true,
                ioRecoveryAttempts = MAX_STREAM_RECOVERY_ATTEMPTS,
                hasNextTrack = false,
            ),
        )
    }

    @Test
    fun expiredLink404ReResolvesForAFreshUrl() {
        // A 404 is an IO-class bad status: the link expired. The
        // shipped copy already promised "Try again to fetch a fresh
        // one" — recovery now does that without being asked.
        assertEquals(
            PlayerErrorRecoveryAction.RE_RESOLVE_IO,
            decide(errorCode = 2004, httpStatus = 404),
        )
    }

    // -- Non-IO errors still surface -------------------------------------

    @Test
    fun nonIoErrorsSurfaceImmediately() {
        // A fresh URL cannot fix a container the device cannot
        // parse or audio it cannot decode — no budget is spent.
        for (code in listOf(3001, 3003, 3004, 4001, 4003, 5000, 6001)) {
            assertEquals(
                "error $code",
                PlayerErrorRecoveryAction.SURFACE_ERROR,
                decide(errorCode = code, hasNextTrack = true),
            )
        }
    }

    @Test
    fun a403OnANonIoErrorKeepsTheShippedFastPath() {
        // The shipped shouldRetryAfterPlayerError looks only at the
        // HTTP status, whatever the player error code; the decision
        // keeps that shape (the 403 branch precedes the IO class).
        assertEquals(
            PlayerErrorRecoveryAction.RE_RESOLVE_REFUSED,
            decide(errorCode = 4003, httpStatus = 403, refusedRetryUsed = false),
        )
    }

    // -- Downloaded / local playback --------------------------------------

    @Test
    fun localFailureFallsBackToStreamingTheSameTrackOnce() {
        assertEquals(
            PlayerErrorRecoveryAction.FALLBACK_TO_STREAM,
            decide(
                errorCode = 4003, // a corrupt file typically dies decoding
                isLocalPlayback = true,
                localFallbackUsed = false,
            ),
        )
        assertEquals(
            PlayerErrorRecoveryAction.FALLBACK_TO_STREAM,
            decide(
                errorCode = 2005, // file not found at the player stage
                isLocalPlayback = true,
                localFallbackUsed = false,
            ),
        )
    }

    @Test
    fun localFailureAfterTheFallbackSurfaces() {
        assertEquals(
            PlayerErrorRecoveryAction.SURFACE_ERROR,
            decide(
                errorCode = 4003,
                isLocalPlayback = true,
                localFallbackUsed = true,
            ),
        )
    }

    // -- The cascade cap (chaos sweep round 2) ---------------------------

    @Test
    fun repeatAllWrapCannotSkipForever() {
        // Under repeat ALL the queue wraps, so hasNextTrack is true
        // at EVERY track — without the cascade cap a queue in which
        // every track IO-fails skipped in a circle forever, each
        // wrap re-arming the budgets. The cap offers queueSize - 1
        // skips per cascade: each track is tried at most once, then
        // the error surfaces.
        assertEquals(
            PlayerErrorRecoveryAction.SKIP_TO_NEXT,
            decide(
                errorCode = 2000, ioRecoveryAttempts = 3,
                hasNextTrack = true, cascadeSkips = 0, queueSize = 2,
            ),
        )
        assertEquals(
            PlayerErrorRecoveryAction.SURFACE_ERROR,
            decide(
                errorCode = 2000, ioRecoveryAttempts = 3,
                hasNextTrack = true, cascadeSkips = 1, queueSize = 2,
            ),
        )
        assertEquals(
            PlayerErrorRecoveryAction.SKIP_TO_NEXT,
            decide(
                errorCode = 2000, ioRecoveryAttempts = 3,
                hasNextTrack = true, cascadeSkips = 3, queueSize = 5,
            ),
        )
        assertEquals(
            PlayerErrorRecoveryAction.SURFACE_ERROR,
            decide(
                errorCode = 2000, ioRecoveryAttempts = 3,
                hasNextTrack = true, cascadeSkips = 4, queueSize = 5,
            ),
        )
        assertEquals(
            PlayerErrorRecoveryAction.SURFACE_ERROR,
            decide(
                errorCode = 2000, ioRecoveryAttempts = 3,
                hasNextTrack = true, cascadeSkips = 99, queueSize = 5,
            ),
        )
    }

    @Test
    fun cascadeCapNeverBlocksRecoveryOrThe403FastPath() {
        // The cap bounds SKIPS only: re-resolves within budget and
        // the shipped 403 fast path are untouched by it, even with
        // the cascade long spent.
        assertEquals(
            PlayerErrorRecoveryAction.RE_RESOLVE_IO,
            decide(
                errorCode = 2000, ioRecoveryAttempts = 0,
                hasNextTrack = true, cascadeSkips = 99, queueSize = 2,
            ),
        )
        assertEquals(
            PlayerErrorRecoveryAction.RE_RESOLVE_REFUSED,
            decide(
                errorCode = 2004, httpStatus = 403, refusedRetryUsed = false,
                ioRecoveryAttempts = 3, hasNextTrack = true,
                cascadeSkips = 99, queueSize = 2,
            ),
        )
    }

    @Test
    fun oneTrackQueueUnderRepeatOneNeverSkipsToItself() {
        // The controller reports hasNextTrack=false for a one-track
        // queue (peek would name the track itself); with the budget
        // spent the failure surfaces — no self-skip loop.
        assertEquals(
            PlayerErrorRecoveryAction.SURFACE_ERROR,
            decide(
                errorCode = 2000, ioRecoveryAttempts = 3,
                hasNextTrack = false, cascadeSkips = 0, queueSize = 1,
            ),
        )
    }

    // -- Stale player errors (chaos sweep round 2) -----------------------

    @Test
    fun playerErrorIsStaleExactlyWhileAResolveIsInFlight() {
        assertTrue(playerErrorIsStale(resolveInFlight = true))
        assertFalse(playerErrorIsStale(resolveInFlight = false))
    }

    @Test
    fun localDecisionBeatsThe403FastPath() {
        // A local item has no HTTP chain of its own; even if a
        // status somehow rides along, the local branch decides.
        assertEquals(
            PlayerErrorRecoveryAction.FALLBACK_TO_STREAM,
            decide(
                errorCode = 2004,
                httpStatus = 403,
                isLocalPlayback = true,
                localFallbackUsed = false,
                refusedRetryUsed = false,
            ),
        )
    }
}
