package io.github.jqssun.airplay.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Branded "AirPlay blue" palette so devices without Material You (e.g. Android TV on
// Android 10/11) get a proper identity instead of the default Material purple.

private val Blue = Color(0xFF2C5CE6)
private val BlueLightOnDark = Color(0xFFADC6FF)
private val Teal = Color(0xFF12B5A6)
private val TealLightOnDark = Color(0xFF7CD8CC)

val BrandLightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE3FF),
    onPrimaryContainer = Color(0xFF001257),
    secondary = Color(0xFF0E9C8F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB7F2E9),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF6B4FD0),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE7DEFF),
    onTertiaryContainer = Color(0xFF22005C),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191B23),
    surface = Color(0xFFFDFCFF),
    onSurface = Color(0xFF191B23),
    surfaceVariant = Color(0xFFE0E2F0),
    onSurfaceVariant = Color(0xFF44464E),
    outline = Color(0xFF75777F),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

val BrandDarkColors = darkColorScheme(
    primary = BlueLightOnDark,
    onPrimary = Color(0xFF002A78),
    primaryContainer = Color(0xFF1A42A8),
    onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = TealLightOnDark,
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF005045),
    onSecondaryContainer = Color(0xFFB7F2E9),
    tertiary = Color(0xFFCEBDFF),
    onTertiary = Color(0xFF380094),
    tertiaryContainer = Color(0xFF5235B7),
    onTertiaryContainer = Color(0xFFE7DEFF),
    background = Color(0xFF111319),
    onBackground = Color(0xFFE2E2EC),
    surface = Color(0xFF111319),
    onSurface = Color(0xFFE2E2EC),
    surfaceVariant = Color(0xFF44464E),
    onSurfaceVariant = Color(0xFFC5C6D5),
    outline = Color(0xFF8F909A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)
