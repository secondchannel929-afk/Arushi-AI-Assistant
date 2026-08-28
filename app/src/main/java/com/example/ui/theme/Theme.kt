package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = ArushiPurplePrimary,
    secondary = ArushiCyan,
    tertiary = ArushiPink,
    background = ArushiDarkBackground,
    surface = ArushiDarkSurface,
    surfaceVariant = ArushiDarkSurfaceVariant,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onTertiary = Color.White,
    onBackground = ArushiTextPrimary,
    onSurface = ArushiTextPrimary,
    onSurfaceVariant = ArushiTextSecondary
)

private val LightColorScheme = darkColorScheme(
    primary = ArushiPurplePrimary,
    secondary = ArushiCyan,
    tertiary = ArushiPink,
    background = ArushiDarkBackground,
    surface = ArushiDarkSurface,
    surfaceVariant = ArushiDarkSurfaceVariant,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onTertiary = Color.White,
    onBackground = ArushiTextPrimary,
    onSurface = ArushiTextPrimary,
    onSurfaceVariant = ArushiTextSecondary
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
