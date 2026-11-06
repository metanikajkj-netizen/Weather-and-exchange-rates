package com.weathercurrency.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF00838F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB4EBF0),
    onSecondaryContainer = Color(0xFF001F24),
    tertiary = Color(0xFF6A5D00),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF1A1C22),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF1A1C22),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    error = Color(0xFFB3261E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF004494),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF4DD9E8),
    onSecondary = Color(0xFF00363A),
    secondaryContainer = Color(0xFF004F56),
    onSecondaryContainer = Color(0xFFB4EBF0),
    tertiary = Color(0xFFD8C64A),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6CF),
    error = Color(0xFFF2B8B5)
)

@Composable
fun WeatherCurrencyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
