package dev.cyberdogs.offleash.playback

/**
 * Playback speed & pitch mapping (Lane G), pure so it is unit-testable
 * without a player.
 *
 * The mapping mirrors Media3's `PlaybackParameters(speed, pitch)` —
 * the pair [QueueController] hands to the live player:
 * - pitch preservation ON: pitch factor 1.0 — the audio is
 *   time-stretched, so tempo changes and pitch does not.
 * - pitch preservation OFF: pitch factor == speed — the audio is
 *   resampled, so pitch rises/falls with tempo (the classic
 *   chipmunk / slow-motion effect).
 * Those are the two useful pitch values Media3's own documentation
 * names for the pitch parameter.
 */

/** The speed steps the player offers, slowest to fastest. */
val PLAYBACK_SPEED_STEPS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

const val MIN_PLAYBACK_SPEED: Float = 0.5f
const val MAX_PLAYBACK_SPEED: Float = 2.0f

/** The (speed, pitch) factor pair for Media3's PlaybackParameters. */
data class SpeedPitch(val speed: Float, val pitch: Float)

/** What the player UI shows and the controller applies. */
data class SpeedUiState(
    val speed: Float = 1.0f,
    val preservePitch: Boolean = true,
)

/**
 * Maps a requested [speed] + [preservePitch] choice to the factor pair.
 * Speeds are clamped into [MIN_PLAYBACK_SPEED]..[MAX_PLAYBACK_SPEED];
 * a non-positive or NaN request reads as normal speed rather than
 * handing the player a value it would reject.
 */
fun playbackParametersFor(speed: Float, preservePitch: Boolean): SpeedPitch {
    val safeSpeed = when {
        speed.isNaN() || speed <= 0f -> 1.0f
        else -> speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
    }
    return SpeedPitch(
        speed = safeSpeed,
        pitch = if (preservePitch) 1.0f else safeSpeed,
    )
}

/** The offered step nearest to [speed] (after the same clamping as the mapping). */
fun nearestSpeedStep(speed: Float): Float {
    val clamped = playbackParametersFor(speed, preservePitch = true).speed
    return PLAYBACK_SPEED_STEPS.minBy { kotlin.math.abs(it - clamped) }
}

/** "1×", "1.25×", "0.5×" — trailing zeros trimmed, never "1.0×". */
fun formatPlaybackSpeed(speed: Float): String {
    val text = if (speed == speed.toInt().toFloat()) {
        speed.toInt().toString()
    } else {
        speed.toString().trimEnd('0').trimEnd('.')
    }
    return "$text×"
}
