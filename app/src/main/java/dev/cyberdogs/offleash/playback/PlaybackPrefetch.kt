package dev.cyberdogs.offleash.playback

import dev.cyberdogs.offleash.core.model.Track
import dev.cyberdogs.offleash.core.playback.PlayQueue

/**
 * Next-track prefetch decisions (2026-11-04 playback-finish run).
 *
 * The acceptance bar the owner set: "every song plays completely
 * through and the next song plays smoothly." Until this run the
 * next track was only resolved when the current one ENDED —
 * [QueueController.onTrackEnded] called resolveAndPlay, which paid
 * the full road cascade (plus the anonymous-road probe ladder)
 * while the listener sat in silence. The gap between tracks was
 * the resolve latency, every time.
 *
 * The fix resolves the next queue entry AHEAD of the transition —
 * when the current track is handed to the player, and again when
 * playback comes within [PREFETCH_LEAD_MS] of the end (for tracks
 * longer than the freshness window) — and hands the transition the
 * already-resolved stream. This file holds the pure decisions so
 * the whole matrix is unit-testable without a player;
 * QueueController keeps the jobs and the side effects.
 *
 * The freshness window deliberately equals the core resolver's
 * own cache TTL (five minutes): a prefetched resolution is never
 * trusted for longer than the resolver itself would re-serve a
 * cached one. Stream URLs live for hours, but the fleet's
 * freshness claim is the cache's, and the prefetch does not get
 * to out-claim it — a stale stash is discarded and the transition
 * resolves live, exactly the pre-prefetch behaviour.
 */
const val PREFETCH_MAX_AGE_MS: Long = 5 * 60 * 1000L

/**
 * How close to the current track's end the position poll starts
 * (re)arming the prefetch: close enough that a full road cascade
 * plus the probe ladder still lands comfortably before the end,
 * far enough that a mid-track poll does not resolve early and let
 * the stash age out before a long track finishes.
 */
const val PREFETCH_LEAD_MS: Long = 60 * 1000L

/**
 * Which queue entry, if any, is worth resolving ahead: the entry
 * [PlayQueue.peekNext] names — deterministic in both modes (the
 * queue materializes its shuffle pass, and a repeat-ALL reshuffle
 * at a pass boundary, precisely so a peek and the following next
 * agree on identity). The current entry itself is never a
 * candidate: under repeat ALL a one-track queue peeks itself, and
 * resolving the playing track again buys nothing.
 */
fun prefetchCandidate(current: Track?, next: Track?): Track? =
    if (next != null && next !== current) next else null

/** Whether a prefetched resolution may serve a transition. */
enum class PrefetchConsume {
    /** Hand the prefetched resolution to the player. */
    TAKE,

    /** Discard it; resolve live through the ordinary path. */
    RESOLVE_FRESH,
}

/**
 * The consume decision for one transition, in order:
 * - A RECOVERY (a retry re-resolve, or any resolve carrying a
 *   resume position) is never served by the stash: the fleet's
 *   force-fresh law says a recovery never re-serves a URL that
 *   was resolved before the failure — the stash is exactly that.
 * - The stash must name the very ENTRY being played, by reference
 *   identity (the 0.8.7 autoplay discipline): an equal-videoId
 *   instance from a replaced queue is a different entry and
 *   resolves live.
 * - The stash must be inside its freshness window (see the file
 *   doc): older is discarded, never stretched.
 */
fun prefetchConsumeVerdict(
    stashEntry: Track?,
    requested: Track,
    isRetry: Boolean,
    recoverySeekMs: Long,
    ageMs: Long,
): PrefetchConsume = when {
    isRetry || recoverySeekMs > 0L -> PrefetchConsume.RESOLVE_FRESH
    stashEntry == null || stashEntry !== requested ->
        PrefetchConsume.RESOLVE_FRESH
    ageMs < 0L || ageMs > PREFETCH_MAX_AGE_MS ->
        PrefetchConsume.RESOLVE_FRESH
    else -> PrefetchConsume.TAKE
}

/**
 * Whether playback is close enough to the current track's end to
 * (re)arm the prefetch. An unknown duration (a stream whose length
 * the player has not established) never arms on position — the
 * hand-off prefetch at track start covers the ordinary case, and
 * guessing at an unknown length would fire the resolve at
 * arbitrary points of a long track.
 */
fun prefetchDueByPosition(durationMs: Long, positionMs: Long): Boolean {
    if (durationMs <= 0L || positionMs < 0L) return false
    val remainingMs = durationMs - positionMs
    return remainingMs in 0..PREFETCH_LEAD_MS
}
