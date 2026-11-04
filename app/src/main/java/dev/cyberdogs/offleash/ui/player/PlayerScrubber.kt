package dev.cyberdogs.offleash.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.cyberdogs.offleash.core.model.Track
import dev.cyberdogs.offleash.core.playback.RepeatMode
import dev.cyberdogs.offleash.playback.QueueUiState
import dev.cyberdogs.offleash.ui.components.formatDuration

/**
 * The player's scrubber and time/up-next decisions (0.9.0 soak,
 * details 03, 04 and 06). The pure pieces are JVM-testable; the
 * composable keeps the Material Slider as its input surface (so
 * accessibility and seek semantics are the platform's), drawn
 * over a Canvas track of our own: a thin resting line inside a
 * 44dp touch target, thickening only while the finger is down.
 */

/** The scrubber's touch-target height, in dp (soak detail 03). */
const val SCRUBBER_TOUCH_TARGET_DP: Int = 44

/** The visible track's thickness at rest, in dp. */
const val SCRUBBER_RESTING_THICKNESS_DP: Int = 3

/** The visible track's thickness while dragging, in dp. */
const val SCRUBBER_DRAGGING_THICKNESS_DP: Int = 7

/** Track thickness for the current drag state. */
fun scrubberTrackThicknessDp(dragging: Boolean): Int =
    if (dragging) SCRUBBER_DRAGGING_THICKNESS_DP else SCRUBBER_RESTING_THICKNESS_DP

/**
 * The right-hand time label (soak detail 04): the time REMAINING,
 * prefixed with a minus — never the track's total duration.
 */
fun remainingTimeLabel(durationMs: Long, positionMs: Long): String {
    val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
    return "-" + formatDuration((remainingMs / 1000).toInt())
}

/**
 * The track the Up next row names (soak detail 06): the queue's
 * own peeked next track, or null when nothing follows. Repeat-one
 * suppresses the row — the honest "next" there is the same track
 * again, and naming it as up next would mislead.
 */
fun upNextTrack(queueState: QueueUiState): Track? =
    if (queueState.repeatMode == RepeatMode.ONE) null else queueState.nextTrack

/**
 * Seek slider: [value] is 0f..1f; commits through
 * [onValueChangeFinished], exactly as the Material Slider did.
 * The Slider itself is transparent — the visible line is the
 * Canvas beneath it, so its thickness can answer the finger
 * without changing the 44dp target the finger gets.
 */
@Composable
fun PlayerScrubber(
    value: Float,
    dragging: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progressColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SCRUBBER_TOUCH_TARGET_DP.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(SCRUBBER_TOUCH_TARGET_DP.dp)) {
            val thickness = scrubberTrackThicknessDp(dragging).dp.toPx()
            val centreY = size.height / 2f
            drawLine(
                color = trackColor,
                start = Offset(0f, centreY),
                end = Offset(size.width, centreY),
                strokeWidth = thickness,
            )
            val progressX = size.width * value.coerceIn(0f, 1f)
            drawLine(
                color = progressColor,
                start = Offset(0f, centreY),
                end = Offset(progressX, centreY),
                strokeWidth = thickness,
            )
            drawCircle(
                color = progressColor,
                radius = thickness * 1.6f,
                center = Offset(progressX, centreY),
            )
        }
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            colors = SliderDefaults.colors(
                thumbColor = Color.Transparent,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
                disabledThumbColor = Color.Transparent,
                disabledActiveTrackColor = Color.Transparent,
                disabledInactiveTrackColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
