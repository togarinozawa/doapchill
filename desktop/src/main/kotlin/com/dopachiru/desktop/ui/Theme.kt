package com.dopachiru.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val scheme = darkColorScheme(
    primary = Color(0xFF9BB8FF),
    onPrimary = Color(0xFF11213F),
    secondary = Color(0xFFFFC98A),
    background = Color(0xFF0B0B12),
    onBackground = Color(0xFFE6E6EE),
    surface = Color(0xFF15151F),
    onSurface = Color(0xFFE6E6EE),
    surfaceVariant = Color(0xFF262633),
    onSurfaceVariant = Color(0xFFA8A8BA),
    error = Color(0xFFFF9A94),
)

@Composable
fun DopaTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, content = content)
