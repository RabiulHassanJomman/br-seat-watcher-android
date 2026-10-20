package com.iqbal.seatwatcher.ui.theme

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

val Accent = Color(0xFF0F7B3F)

/** Semantic colors that stay fixed regardless of dynamic color. */
@Immutable
data class StatusColors(
    val good: Color,
    val onGood: Color,
    val goodContainer: Color,
    val bad: Color,
    val warn: Color,
    val warnContainer: Color,
    val muted: Color,
)

private val LightStatus = StatusColors(
    good = Color(0xFF0F7B3F), onGood = Color.White, goodContainer = Color(0xFFCDEFD9),
    bad = Color(0xFFB3261E), warn = Color(0xFFB45309), warnContainer = Color(0xFFFFE7C7), muted = Color(0xFF6B6B6B),
)
private val DarkStatus = StatusColors(
    good = Color(0xFF7FD9A0), onGood = Color(0xFF00391C), goodContainer = Color(0xFF0B4F2A),
    bad = Color(0xFFF2B8B5), warn = Color(0xFFFFB86B), warnContainer = Color(0xFF5A3200), muted = Color(0xFFA8A8A8),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val LightScheme = lightColorScheme(
    primary = Accent, onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F2C1), onPrimaryContainer = Color(0xFF00210E),
    secondary = Color(0xFF4F6354), tertiary = Color(0xFF3B6470),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8BD6A6), onPrimary = Color(0xFF00391C),
    primaryContainer = Color(0xFF00522B), onPrimaryContainer = Color(0xFFA6F2C1),
    secondary = Color(0xFFB6CCB9), tertiary = Color(0xFFA3CDDA),
)

@Composable
fun SeatWatcherTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
