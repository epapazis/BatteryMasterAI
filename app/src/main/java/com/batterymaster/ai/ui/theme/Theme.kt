package com.batterymaster.ai.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.batterymaster.ai.data.prefs.ThemeMode

private val Green = Color(0xFF0F8A6A)
private val GreenLight = Color(0xFF3DDC97)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F0D9),
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF4C6359),
    tertiary = Color(0xFF3E6374),
    background = Color(0xFFF7F9F8),
    surface = Color(0xFFF7F9F8),
    surfaceVariant = Color(0xFFDCE5DF),
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513D),
    onPrimaryContainer = Color(0xFFB7F0D9),
    secondary = Color(0xFFB3CCC0),
    tertiary = Color(0xFFA6CCE0),
    background = Color(0xFF0E1412),
    surface = Color(0xFF0E1412),
    surfaceVariant = Color(0xFF3F4945),
)

/** Fixed semantic colours for data classes and chart series (consistent in both themes). */
@Immutable
data class DataColors(
    val measured: Color,
    val system: Color,
    val calculated: Color,
    val estimated: Color,
    val prediction: Color,
    val good: Color,
    val notice: Color,
    val warning: Color,
    val series: List<Color>,
)

private val LightData = DataColors(
    measured = Color(0xFF0F8A6A), system = Color(0xFF2F6FB0), calculated = Color(0xFF6A5ACD),
    estimated = Color(0xFFB7791F), prediction = Color(0xFFB23A7A),
    good = Color(0xFF1E8E3E), notice = Color(0xFFB7791F), warning = Color(0xFFC5221F),
    series = listOf(Color(0xFF0F8A6A), Color(0xFF2F6FB0), Color(0xFFD9822B), Color(0xFF8E44AD)),
)

private val DarkData = DataColors(
    measured = Color(0xFF3DDC97), system = Color(0xFF7FB2F0), calculated = Color(0xFFB0A4F5),
    estimated = Color(0xFFF2B95C), prediction = Color(0xFFF08BC0),
    good = Color(0xFF6DD58C), notice = Color(0xFFF2B95C), warning = Color(0xFFFF8A80),
    series = listOf(Color(0xFF3DDC97), Color(0xFF7FB2F0), Color(0xFFF2B95C), Color(0xFFCE93D8)),
)

val LocalDataColors = staticCompositionLocalOf { LightData }

@Composable
fun BatteryMasterTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalDataColors provides if (dark) DarkData else LightData) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
