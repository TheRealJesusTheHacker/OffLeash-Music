package dev.cyberdogs.offleash.core.playback

/**
 * The user's stream-quality preference (Wave 2): which direct format
 * [StreamResolver] picks out of a player response. The caps are applied
 * to the same pool HIGH chooses from — the direct audio formats when
 * the response carries any, otherwise every direct format (the pool
 * rule Phase 1 shipped with; quality never widens or narrows the pool).
 */
enum class QualityPreference {

    /**
     * Data-saver: the highest-bitrate format at or below 80_000 bps
     * when one exists, otherwise the LOWEST-bitrate format available —
     * a track that only ships fat formats still plays, at its smallest.
     */
    LOW,

    /**
     * The highest-bitrate format at or below 160_000 bps when one
     * exists, otherwise exactly [HIGH]'s pick.
     */
    BALANCED,

    /**
     * Phase 1 behavior, unchanged: the highest-bitrate format, with
     * audio/mp4 winning bitrate ties.
     */
    HIGH,
}
