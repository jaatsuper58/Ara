package com.ara.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Ara palette — indigo primary, high-contrast neutrals (accessibility-checked).
private val Indigo = Color(0xFF4F46E5)
private val IndigoDark = Color(0xFF818CF8)
private val Teal = Color(0xFF14B8A6)
private val SurfaceLight = Color(0xFFF8F7FC)
private val SurfaceDark = Color(0xFF121218)
private val BubbleOutLight = Color(0xFFE0E7FF)
private val BubbleOutDark = Color(0xFF312E81)

private val LightColors = lightColorScheme(
    primary = Indigo,
    secondary = Teal,
    surface = SurfaceLight,
    surfaceVariant = Color(0xFFEEEDF6),
    background = SurfaceLight,
)

private val DarkColors = darkColorScheme(
    primary = IndigoDark,
    secondary = Teal,
    surface = SurfaceDark,
    surfaceVariant = Color(0xFF1E1E28),
    background = SurfaceDark,
)

@Composable
fun AraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

/** Bubble colors resolve per theme for message rendering. */
@Composable
fun outgoingBubbleColor(): Color = if (isSystemInDarkTheme()) BubbleOutDark else BubbleOutLight
