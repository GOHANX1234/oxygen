package com.oxygens.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val OxygenBlue = Color(0xFF1B1F3B)
private val OxygenAccent = Color(0xFF4C6FFF)

private val LightColors = lightColorScheme(
    primary = OxygenBlue,
    secondary = OxygenAccent,
)

private val DarkColors = darkColorScheme(
    primary = OxygenAccent,
    secondary = OxygenBlue,
)

@Composable
fun OxygenSTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
