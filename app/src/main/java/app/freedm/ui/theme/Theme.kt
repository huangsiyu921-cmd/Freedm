package app.freedm.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Blue = Color(0xFF2F6FEB)
private val BlueDark = Color(0xFF9CC0FF)

private val Light = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE6FF),
    onPrimaryContainer = Color(0xFF001945),
    secondary = Color(0xFF565E71),
    surface = Color(0xFFFDFCFF),
    surfaceVariant = Color(0xFFE1E2EC),
    background = Color(0xFFFDFCFF),
)

private val Dark = darkColorScheme(
    primary = BlueDark,
    onPrimary = Color(0xFF00315C),
    primaryContainer = Color(0xFF12468F),
    onPrimaryContainer = Color(0xFFDDE6FF),
    secondary = Color(0xFFBEC6DC),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF44474F),
    background = Color(0xFF111318),
)

/** Theme preference keys, shared with SettingsScreen. */
const val THEME_MODE = "theme_mode"
const val THEME_DYNAMIC = "dynamic_color"

/**
 * @param mode "system" | "light" | "dark"
 * @param dynamicColor Material You palette taken from the wallpaper (Android 12+)
 */
@Composable
fun FreeDmTheme(
    mode: String = "system",
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val ctx = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)

        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** True when [mode] resolves to a dark UI, used for the system bar icons. */
@Composable
fun isDarkTheme(mode: String): Boolean = when (mode) {
    "light" -> false
    "dark" -> true
    else -> isSystemInDarkTheme()
}
