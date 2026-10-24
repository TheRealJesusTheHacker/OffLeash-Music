package dev.cyberdogs.offleash.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cyberdogs black-and-tan palette — SPEC §9 tokens. No purple anywhere.
private val OffleashColors = darkColorScheme(
    primary = Color(0xFFD9A441),
    onPrimary = Color(0xFF1A1206),
    secondary = Color(0xFFB98A4A),
    onSecondary = Color(0xFF1A1206),
    tertiary = Color(0xFFEBCB8B),
    onTertiary = Color(0xFF1A1206),
    background = Color(0xFF0D0B09),
    onBackground = Color(0xFFF4EDE1),
    surface = Color(0xFF16120E),
    onSurface = Color(0xFFF4EDE1),
    surfaceVariant = Color(0xFF211A13),
    onSurfaceVariant = Color(0xFFB9AB94),
    error = Color(0xFFB03A2E),
    onError = Color(0xFFFFFFFF),
)

@Composable
fun OffleashTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OffleashColors,
        content = content,
    )
}
