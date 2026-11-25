package com.courtvision.ai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.courtvision.ai.data.ThemeMode

val Lime = Color(0xFFC6F432)
val LimeDark = Color(0xFF5E7A00)
val Graphite = Color(0xFF0E1110)
val GraphiteSurface = Color(0xFF181D1A)
val GraphiteSurface2 = Color(0xFF222925)
val OutRed = Color(0xFFFF5A5F)
val InGreen = Color(0xFF3DDC84)
val Unsure = Color(0xFFFFC145)
val CourtBlue = Color(0xFF1F4E79)

private val DarkScheme = darkColorScheme(
    primary = Lime, onPrimary = Graphite, secondary = Color(0xFF9AD0FF), onSecondary = Graphite,
    background = Graphite, onBackground = Color(0xFFE8EDE9), surface = GraphiteSurface, onSurface = Color(0xFFE8EDE9),
    surfaceVariant = GraphiteSurface2, onSurfaceVariant = Color(0xFFB7C2BB), error = OutRed,
    primaryContainer = Color(0xFF34420A), onPrimaryContainer = Lime,
)

private val LightScheme = lightColorScheme(
    primary = LimeDark, onPrimary = Color.White, secondary = Color(0xFF1F5F99),
    background = Color(0xFFF7F9F4), surface = Color.White, surfaceVariant = Color(0xFFE9EEE3),
    primaryContainer = Lime, onPrimaryContainer = Graphite, error = Color(0xFFC62828),
)

@Composable
fun CourtVisionTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}
