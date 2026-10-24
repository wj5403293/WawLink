package dev.wawlink.ui

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun agentSystemColorScheme(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    // Wallpaper / overlay and night-mode changes must invalidate native Monet roles.
    val configuration = LocalConfiguration.current
    return remember(context, configuration, dark) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
}
