package com.example.telepathytag.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ============================================================
// Fixed Dark Color Scheme (Section 4)
// ============================================================
private val HaloDarkColorScheme = darkColorScheme(
    primary = PrimaryGreen,
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF003D1A),
    onPrimaryContainer = Color(0xFFB0FFD0),
    inversePrimary = PrimaryGreenDark,
    secondary = AccentGreen,
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF003D20),
    onSecondaryContainer = Color(0xFFB0FFD0),
    tertiary = Color(0xFF1DE9B6),
    onTertiary = Color(0xFF000000),
    background = BackgroundBlack,
    onBackground = TextPrimary,
    surface = CardDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = TextSecondary,
    error = StatusLost,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF4A1010),
    onErrorContainer = Color(0xFFFFCCCC),
    outline = Color(0xFF2A332F),
    outlineVariant = Color(0xFF1A2420),
    inverseSurface = Color(0xFFE0E0E0),
    inverseOnSurface = Color(0xFF1A1A1A)
)

@Composable
fun TelepathyTagTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = HaloDarkColorScheme,
        typography = HaloTypography,
        content = content
    )
}
