package com.dopachiru.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Purple = Color(0xFF7C6BF5)
private val Amber = Color(0xFFF5C86B)
private val Ink = Color(0xFF14141F)
private val Surface = Color(0xFF1E1E2E)

private val DarkColors = darkColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    secondary = Amber,
    onSecondary = Ink,
    background = Ink,
    onBackground = Color(0xFFECECF2),
    surface = Surface,
    onSurface = Color(0xFFECECF2),
    surfaceVariant = Color(0xFF2A2A3C),
    onSurfaceVariant = Color(0xFFB6B6C6),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    secondary = Color(0xFFB98A17),
    background = Color(0xFFF7F7FB),
    surface = Color.White,
)

@Composable
fun DopaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

/** ブロック画面は端末のテーマに関わらず暗く出す。 */
@Composable
fun DopaBlockTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
