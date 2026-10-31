package dev.cyberdogs.offleash.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cyberdogs.offleash.R
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * The Halloween cold-launch splash — the Moon Howl (owner's
 * pick, 2026-10-31): the Cyberdogs Doberman thrown up at a
 * huge full moon, bats crossing the disc, a starfield behind.
 * It replaces the standard splash ONLY inside the Halloween
 * window (device-local Oct 30 – Nov 1, every year); the date
 * decision lives in
 * [dev.cyberdogs.offleash.playback.HalloweenSplash], the
 * show/suppress decision stays with
 * [dev.cyberdogs.offleash.playback.SplashGate], and the
 * duration / tap-to-skip contract is the standard splash's:
 * 3.8 seconds, a tap anywhere skips at once.
 *
 * The composition is rebuilt from the app's real brand asset
 * (the geometric Doberman head, `cyberdogs_logo_alpha`), the
 * head tipped up at the moon with a pale moonlit rim behind
 * it; the moon, glow, stars, and bats are drawn and animated
 * natively — the glow breathes, the bats drift and flap, the
 * head sways almost imperceptibly. The OFFLEASH wordmark is
 * set in Pirata One, a gothic display face (SIL Open Font
 * License 1.1; the license ships in the APK at
 * res/raw/pirata_one_ofl.txt), and the "Happy Halloween"
 * greeting is animated: its letters ease in one by one,
 * rising as a warm glow flares behind each and settles soft.
 *
 * When the system animator scale is 0 (reduced motion), the
 * scene renders still with the greeting fully shown; the
 * duration and the tap-to-skip are unchanged.
 */
@Composable
fun HalloweenSplashScreen(onSkip: () -> Unit) {
    val context = LocalContext.current
    val reducedMotion = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
    var phase by remember { mutableFloatStateOf(0f) }
    if (!reducedMotion) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(AMBIENT_TICK_MS)
                phase += 0.05f
                if (phase > 7200f) {
                    phase = 0f
                }
            }
        }
    }
    val greeting = remember { Animatable(if (reducedMotion) 1f else 0f) }
    if (!reducedMotion) {
        LaunchedEffect(Unit) {
            greeting.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 1700,
                    delayMillis = 350,
                    easing = FastOutSlowInEasing,
                ),
            )
        }
    }
    val stars = remember { buildStars() }
    val tan = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NIGHT_SKY)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Skip the splash",
            ) { onSkip() },
        contentAlignment = Alignment.TopCenter,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawMoonScene(stars, phase)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(58.dp))
            Box(contentAlignment = Alignment.TopCenter) {
                Image(
                    painter = painterResource(id = R.drawable.cyberdogs_logo_alpha),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(MOON_PALE, BlendMode.SrcIn),
                    alpha = 0.20f,
                    modifier = Modifier
                        .size(LOGO_SIZE)
                        .offset(y = (-5).dp),
                )
                Image(
                    painter = painterResource(id = R.drawable.cyberdogs_logo_alpha),
                    contentDescription = "Cyberdogs logo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(LOGO_SIZE)
                        .graphicsLayer {
                            rotationZ = -5f +
                                (sin(phase.toDouble() * 0.45) * 1.4).toFloat()
                        },
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "OFFLEASH",
                style = TextStyle(
                    fontFamily = SpookyDisplay,
                    fontWeight = FontWeight.Normal,
                    fontSize = 54.sp,
                    letterSpacing = 2.sp,
                    shadow = Shadow(
                        color = EMBER.copy(alpha = 0.35f),
                        offset = Offset.Zero,
                        blurRadius = 14f,
                    ),
                ),
                color = MOON_PALE,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "CYBERDOGS",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 7.sp,
                color = tan,
            )
            Spacer(Modifier.height(16.dp))
            AnimatedGreeting(progress = greeting.value)
        }
    }
}

/**
 * The greeting, letter by letter: each letter's slice of the
 * shared [progress] drives its fade, its rise, and a glow
 * that flares as the letter lands and settles to a soft
 * ember halo. Rendered clear of the wordmark, below it.
 */
@Composable
private fun AnimatedGreeting(progress: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (i in GREETING.indices) {
            val ch = GREETING[i]
            if (ch == ' ') {
                Spacer(Modifier.width(11.dp))
            } else {
                val start = i * 0.045f
                val local = ((progress - start) / 0.37f).coerceIn(0f, 1f)
                val glow = 3f + 22f * local * (1f - local)
                Text(
                    text = ch.toString(),
                    style = TextStyle(
                        fontFamily = SpookyDisplay,
                        fontWeight = FontWeight.Normal,
                        fontSize = 31.sp,
                        shadow = Shadow(
                            color = EMBER.copy(alpha = 0.85f * local),
                            offset = Offset.Zero,
                            blurRadius = glow,
                        ),
                    ),
                    color = EMBER.copy(alpha = local),
                    modifier = Modifier.offset(y = ((1f - local) * 12f).dp),
                )
            }
        }
    }
}

/** The night sky: stars, the breathing moon, its craters, the bats. */
private fun DrawScope.drawMoonScene(stars: List<StarSpec>, phase: Float) {
    for (star in stars) {
        val twinkle = 0.5f + 0.5f *
            sin(phase.toDouble() * 0.9 + star.twinkle.toDouble()).toFloat()
        drawCircle(
            color = STARLIGHT.copy(alpha = 0.25f + 0.45f * twinkle),
            radius = star.radius,
            center = Offset(star.x * size.width, star.y * size.height),
        )
    }
    val moonCenter = Offset(size.width * 0.5f, size.height * 0.335f)
    val moonRadius = size.width * 0.415f
    val breath = 0.5f + 0.5f * sin(phase.toDouble() * 0.6).toFloat()
    val haloAlpha = 0.07f + 0.05f * breath
    drawCircle(
        color = MOON_PALE.copy(alpha = haloAlpha * 0.6f),
        radius = moonRadius * 1.5f,
        center = moonCenter,
    )
    drawCircle(
        color = MOON_PALE.copy(alpha = haloAlpha),
        radius = moonRadius * 1.22f,
        center = moonCenter,
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(MOON_CORE, MOON_FACE, MOON_EDGE),
            center = moonCenter,
            radius = moonRadius,
        ),
        radius = moonRadius,
        center = moonCenter,
    )
    drawCircle(
        color = MOON_CRATER.copy(alpha = 0.30f),
        radius = moonRadius * 0.14f,
        center = moonCenter + Offset(-moonRadius * 0.36f, -moonRadius * 0.20f),
    )
    drawCircle(
        color = MOON_CRATER.copy(alpha = 0.24f),
        radius = moonRadius * 0.09f,
        center = moonCenter + Offset(moonRadius * 0.30f, moonRadius * 0.28f),
    )
    drawCircle(
        color = MOON_CRATER.copy(alpha = 0.20f),
        radius = moonRadius * 0.07f,
        center = moonCenter + Offset(moonRadius * 0.12f, -moonRadius * 0.44f),
    )
    drawCircle(
        color = MOON_CRATER.copy(alpha = 0.18f),
        radius = moonRadius * 0.05f,
        center = moonCenter + Offset(-moonRadius * 0.08f, moonRadius * 0.42f),
    )
    val spanScale = size.width / 900f
    for (i in 0 until BAT_COUNT) {
        val drift = sin(phase.toDouble() * 0.30 + i * 1.93).toFloat() * 12f
        val bob = cos(phase.toDouble() * 0.42 + i * 2.39).toFloat() * 7f
        val center = moonCenter + Offset(
            (BAT_ANCHOR_X[i] - 0.5f) * 2f * moonRadius * 0.94f + drift,
            (BAT_ANCHOR_Y[i] - 0.5f) * 2f * moonRadius * 0.94f + bob,
        )
        val flap = sin(phase.toDouble() * 3.1 + i * 2.07).toFloat() *
            BAT_SPAN[i] * spanScale * 0.30f
        drawBat(center, BAT_SPAN[i] * spanScale, flap, BAT_BLACK)
    }
}

/** One bat silhouette: two wings around a small body, [flap] lifts the wingtips. */
private fun DrawScope.drawBat(center: Offset, span: Float, flap: Float, color: Color) {
    val path = Path()
    val half = span * 0.5f
    val tipY = center.y - span * 0.14f - flap
    path.moveTo(center.x - half, tipY)
    path.quadraticBezierTo(
        center.x - span * 0.30f, center.y + span * 0.12f,
        center.x - span * 0.07f, center.y - span * 0.02f,
    )
    path.quadraticBezierTo(
        center.x, center.y + span * 0.16f,
        center.x + span * 0.07f, center.y - span * 0.02f,
    )
    path.quadraticBezierTo(
        center.x + span * 0.30f, center.y + span * 0.12f,
        center.x + half, tipY,
    )
    path.quadraticBezierTo(
        center.x, center.y - span * 0.34f - flap * 0.35f,
        center.x - half, tipY,
    )
    path.close()
    drawPath(path, color = color)
}

/** One star's fixed place in the sky; only its twinkle phase animates. */
private class StarSpec(
    val x: Float,
    val y: Float,
    val radius: Float,
    val twinkle: Float,
)

private fun buildStars(): List<StarSpec> {
    val random = Random(1031)
    return List(70) {
        StarSpec(
            x = random.nextFloat(),
            y = random.nextFloat() * 0.62f,
            radius = 0.8f + random.nextFloat() * 1.5f,
            twinkle = random.nextFloat() * 6.28f,
        )
    }
}

/**
 * Pirata One — a gothic blackletter display face by Rodrigo
 * Fuenzalida and Nicolas Massi, licensed under the SIL Open
 * Font License 1.1 (bundled at res/raw/pirata_one_ofl.txt).
 * The spooky display face of the Halloween splash: wordmark
 * and greeting both set in it.
 */
private val SpookyDisplay = FontFamily(
    Font(R.font.pirata_one_regular, FontWeight.Normal),
)

private const val GREETING: String = "Happy Halloween"

private val LOGO_SIZE = 296.dp

private const val AMBIENT_TICK_MS = 50L

private const val BAT_COUNT: Int = 6

private val BAT_ANCHOR_X: FloatArray =
    floatArrayOf(0.30f, 0.62f, 0.74f, 0.44f, 0.82f, 0.55f)

private val BAT_ANCHOR_Y: FloatArray =
    floatArrayOf(0.24f, 0.16f, 0.30f, 0.40f, 0.44f, 0.55f)

private val BAT_SPAN: FloatArray =
    floatArrayOf(42f, 30f, 48f, 26f, 34f, 22f)

private val NIGHT_SKY = Color(0xFF030409)
private val MOON_PALE = Color(0xFFF4EDDA)
private val MOON_CORE = Color(0xFFFBF6E3)
private val MOON_FACE = Color(0xFFEFE3C2)
private val MOON_EDGE = Color(0xFFD8C9A3)
private val MOON_CRATER = Color(0xFFC9B992)
private val STARLIGHT = Color(0xFFDDE6F5)
private val BAT_BLACK = Color(0xFF0A0C12)
private val EMBER = Color(0xFFE8973C)
