package dev.cyberdogs.offleash.ui.player

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The player's track title (0.9.0 soak, detail 02): a title that
 * fits rests centred, exactly as before. A title that overflows
 * scrolls ONCE — after a beat, across to reveal its end, back to
 * its start — and then rests at the start for good. It never
 * loops. When the device asks for reduced motion (the animator
 * duration scale is 0), the title renders static with an ellipsis
 * instead: no scroll is ever the price of reading a title.
 */
@Composable
fun ScrollingOnceText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        } catch (e: Exception) {
            false
        }
    }
    var viewportWidthPx by remember { mutableStateOf(0) }
    val textMeasurer = rememberTextMeasurer()
    val textWidthPx = remember(text, style, textMeasurer) {
        textMeasurer.measure(
            text = text,
            style = style,
            overflow = TextOverflow.Clip,
            softWrap = false,
            maxLines = 1,
        ).size.width
    }
    val offset = remember(text) { Animatable(0f) }
    var scrolling by remember(text) { mutableStateOf(false) }
    val overflowPx = (textWidthPx - viewportWidthPx).coerceAtLeast(0)

    LaunchedEffect(text, overflowPx, reducedMotion) {
        if (overflowPx <= 0 || reducedMotion) return@LaunchedEffect
        delay(700)
        scrolling = true
        val durationMs = (overflowPx / 3).coerceIn(900, 4200)
        offset.animateTo(overflowPx.toFloat(), tween(durationMillis = durationMs))
        delay(650)
        offset.animateTo(0f, tween(durationMillis = 450))
        scrolling = false
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .onSizeChanged { viewportWidthPx = it.width },
        contentAlignment = if (overflowPx > 0) Alignment.CenterStart else Alignment.Center,
    ) {
        Text(
            text = text,
            style = style,
            color = color,
            textAlign = TextAlign.Start,
            maxLines = 1,
            softWrap = false,
            overflow = if (scrolling || reducedMotion) {
                TextOverflow.Clip
            } else {
                TextOverflow.Ellipsis
            },
            modifier = Modifier
                .wrapContentWidth(unbounded = true)
                .offset { IntOffset(-offset.value.roundToInt(), 0) },
        )
    }
}
