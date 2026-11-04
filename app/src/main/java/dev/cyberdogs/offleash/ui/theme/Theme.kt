package dev.cyberdogs.offleash.ui.theme

import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cyberdogs black-and-tan palette — SPEC §9 tokens. No purple anywhere.
//
// Every role is set explicitly: the roles this scheme used to leave
// unset (the container family above all) fell back to Material's
// baseline palette, whose dark secondaryContainer is a purple-grey
// (0xFF4A4458) — it rendered as the selected-tab indicator pill and
// the selected FilterChip fill, a purple the house style forbids.
//
// The error pair is chosen for BOTH of its uses: `error` renders as
// text on the dark backgrounds (the Library's failed-download line)
// and as the player error banner's background with `onError` text on
// it. The old dark red passed only the banner use (white on it,
// 6.0:1) while failing as text (3.3:1 on the background). No single
// red passes both ways at 4.5:1 with white text, so the banner takes
// dark text on a brighter red instead — every pair below is ≥ 4.5:1
// for normal text (pinned by UiSweepContractTest's contrast check).
private val OffleashColors = darkColorScheme(
    primary = Color(0xFFD9A441),
    onPrimary = Color(0xFF1A1206),
    primaryContainer = Color(0xFF4A3410),
    onPrimaryContainer = Color(0xFFF2D492),
    secondary = Color(0xFFB98A4A),
    onSecondary = Color(0xFF1A1206),
    secondaryContainer = Color(0xFF3A2C1B),
    onSecondaryContainer = Color(0xFFE8D5B5),
    tertiary = Color(0xFFEBCB8B),
    onTertiary = Color(0xFF1A1206),
    tertiaryContainer = Color(0xFF4A3A1E),
    onTertiaryContainer = Color(0xFFF2D492),
    background = Color(0xFF0D0B09),
    onBackground = Color(0xFFF4EDE1),
    surface = Color(0xFF16120E),
    onSurface = Color(0xFFF4EDE1),
    surfaceVariant = Color(0xFF211A13),
    onSurfaceVariant = Color(0xFFB9AB94),
    surfaceDim = Color(0xFF0D0B09),
    surfaceBright = Color(0xFF2A231B),
    surfaceContainerLowest = Color(0xFF0D0B09),
    surfaceContainerLow = Color(0xFF16120E),
    surfaceContainer = Color(0xFF1C1712),
    surfaceContainerHigh = Color(0xFF211A13),
    surfaceContainerHighest = Color(0xFF2A231B),
    error = Color(0xFFE0665E),
    onError = Color(0xFF1A1206),
    errorContainer = Color(0xFF5C1A14),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8A7B63),
    outlineVariant = Color(0xFF4A4136),
    inverseSurface = Color(0xFFF4EDE1),
    inverseOnSurface = Color(0xFF1A1206),
    inversePrimary = Color(0xFF7A5A14),
    scrim = Color(0xFF000000),
)

// GUI elevation pass (2026-11-04, owner-approved): the app's first
// named type scale, replacing the Material defaults the theme used to
// ride on. Sizes are the design tokens from the elevation frames:
// screen titles step up, section heads get a consistent SemiBold, and
// labelSmall is the letterspaced eyebrow style. Weights only — the
// platform sans stays the family, as shipped.
private val OffleashTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 42.sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.5.sp,
    ),
)

// Corner language for the elevation: cards land on `large` (20 dp),
// list art on the shared 12 dp clip in ArtworkImage, pills stay
// full-round by their own shapes.
private val OffleashShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun OffleashTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OffleashColors,
        typography = OffleashTypography,
        shapes = OffleashShapes,
        content = content,
    )
}
