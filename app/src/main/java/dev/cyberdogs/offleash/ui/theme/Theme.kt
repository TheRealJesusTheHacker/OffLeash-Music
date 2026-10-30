package dev.cyberdogs.offleash.ui.theme

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

@Composable
fun OffleashTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OffleashColors,
        content = content,
    )
}
