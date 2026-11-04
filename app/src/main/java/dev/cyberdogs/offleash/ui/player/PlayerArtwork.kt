package dev.cyberdogs.offleash.ui.player

import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Cover-colour discipline for the player (0.9.0 soak, detail 01):
 * the artwork's colour may tint ONLY the player's backdrop and
 * stage glow, always darkened and muted toward the app's own
 * near-black background, so every text pairing the player already
 * had keeps its contrast (the theme's weakest player pairing was
 * 5.50:1 against the plain background; the glow's luminance cap
 * keeps pairings in that class — the pure functions below are the
 * testable proof). Purple-band hues — the fleet's banned colour —
 * fall back to the tan glow, as does any artwork that fails to
 * load or carries no usable colour.
 */

/** The player background the glow is blended toward (theme background). */
const val PLAYER_BACKGROUND_ARGB: Int = 0xFF0D0B09.toInt()

/** The tan fallback glow, pre-darkened from the theme's primary tan. */
const val COVER_GLOW_FALLBACK_ARGB: Int = 0xFF332921.toInt()

/** Hues (degrees) treated as the purple band: [PURPLE_BAND_START]..[PURPLE_BAND_END]. */
private const val PURPLE_BAND_START = 240f
private const val PURPLE_BAND_END = 320f

/** The most of the cover colour the glow may carry after blending. */
private const val GLOW_COLOUR_FRACTION = 0.45f

/** Saturation is muted to this fraction of the artwork's own. */
private const val GLOW_SATURATION_FRACTION = 0.55f

/** Value (brightness) cap for the extracted colour before blending. */
private const val GLOW_VALUE_CAP = 0.32f

/**
 * The glow colour for a set of sampled artwork pixels (ARGB ints):
 * a saturation-weighted average of the opaque pixels, muted and
 * darkened, then blended toward the player background. Purple-band
 * results and empty/unusable input yield [COVER_GLOW_FALLBACK_ARGB].
 */
fun coverGlowArgb(pixels: IntArray): Int {
    var count = 0
    var rSum = 0.0
    var gSum = 0.0
    var bSum = 0.0
    var wSum = 0.0
    for (pixel in pixels) {
        val alpha = (pixel ushr 24) and 0xFF
        if (alpha < 128) continue
        val r = ((pixel shr 16) and 0xFF) / 255f
        val g = ((pixel shr 8) and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val saturation = if (mx == 0f) 0f else (mx - mn) / mx
        val weight = 0.25 + saturation
        rSum += r * weight
        gSum += g * weight
        bSum += b * weight
        wSum += weight
        count++
    }
    if (count == 0 || wSum == 0.0) return COVER_GLOW_FALLBACK_ARGB
    val r = (rSum / wSum).toFloat()
    val g = (gSum / wSum).toFloat()
    val b = (bSum / wSum).toFloat()
    val (hue, saturation, value) = rgbToHsv(r, g, b)
    if (hue in PURPLE_BAND_START..PURPLE_BAND_END) return COVER_GLOW_FALLBACK_ARGB
    val (mr, mg, mb) = hsvToRgb(
        hue,
        saturation * GLOW_SATURATION_FRACTION,
        min(value, GLOW_VALUE_CAP),
    )
    val bgR = ((PLAYER_BACKGROUND_ARGB shr 16) and 0xFF) / 255f
    val bgG = ((PLAYER_BACKGROUND_ARGB shr 8) and 0xFF) / 255f
    val bgB = (PLAYER_BACKGROUND_ARGB and 0xFF) / 255f
    val outR = mr * GLOW_COLOUR_FRACTION + bgR * (1f - GLOW_COLOUR_FRACTION)
    val outG = mg * GLOW_COLOUR_FRACTION + bgG * (1f - GLOW_COLOUR_FRACTION)
    val outB = mb * GLOW_COLOUR_FRACTION + bgB * (1f - GLOW_COLOUR_FRACTION)
    return (0xFF shl 24) or
        ((outR * 255f).toInt().coerceIn(0, 255) shl 16) or
        ((outG * 255f).toInt().coerceIn(0, 255) shl 8) or
        (outB * 255f).toInt().coerceIn(0, 255)
}

private fun rgbToHsv(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val delta = mx - mn
    val hue = when {
        delta == 0f -> 0f
        mx == r -> 60f * (((g - b) / delta) % 6f)
        mx == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    val normalizedHue = if (hue < 0f) hue + 360f else hue
    val saturation = if (mx == 0f) 0f else delta / mx
    return Triple(normalizedHue, saturation, mx)
}

private fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Float, Float, Float> {
    val c = v * s
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = v - c
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Triple(r + m, g + m, b + m)
}

/** WCAG relative luminance of an ARGB colour. */
fun relativeLuminanceArgb(argb: Int): Double {
    fun channel(v: Int): Double {
        val f = v / 255.0
        return if (f <= 0.03928) f / 12.92 else ((f + 0.055) / 1.055).pow(2.4)
    }
    val r = channel((argb shr 16) and 0xFF)
    val g = channel((argb shr 8) and 0xFF)
    val b = channel(argb and 0xFF)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** WCAG contrast ratio between two ARGB colours (1.0..21.0). */
fun contrastRatioArgb(first: Int, second: Int): Double {
    val l1 = relativeLuminanceArgb(first)
    val l2 = relativeLuminanceArgb(second)
    val lighter = max(l1, l2)
    val darker = min(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)
}

/**
 * The player's cover glow for [heroUrl] (falling back to the
 * row-sized [rowUrl]): loads a small sample of the artwork through
 * the app's Coil loader and reduces it with [coverGlowArgb]. Any
 * failure keeps the tan fallback — the glow is a decoration, never
 * a load blocker.
 */
@Composable
fun rememberCoverGlowColor(heroUrl: String?, rowUrl: String?): Color {
    val context = LocalContext.current
    val url = heroUrl ?: rowUrl
    var glowArgb by remember(url) { mutableStateOf(COVER_GLOW_FALLBACK_ARGB) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        try {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(48, 48)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request)
            val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
            if (bitmap != null) {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                glowArgb = coverGlowArgb(pixels)
            }
        } catch (e: Exception) {
            // The tan fallback already stands.
        }
    }
    return Color(glowArgb)
}
