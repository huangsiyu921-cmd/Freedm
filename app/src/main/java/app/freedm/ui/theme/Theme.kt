package app.freedm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

@Composable
fun FreeDmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        content = content,
    )
}
