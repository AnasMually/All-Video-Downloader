package com.anas_mugally.videodownloader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.anas_mugally.videodownloader.domain.ThemeMode

private val Light = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF4F46B7),
    secondary = androidx.compose.ui.graphics.Color(0xFF5F5D72),
    tertiary = androidx.compose.ui.graphics.Color(0xFF7C5265),
)

private val Dark = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFC7C0FF),
    secondary = androidx.compose.ui.graphics.Color(0xFFC8C4DC),
    tertiary = androidx.compose.ui.graphics.Color(0xFFEDB8CB),
)

@Composable
fun DownloaderTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) {
        Dark
    } else {
        Light
    }
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content,
    )
}
