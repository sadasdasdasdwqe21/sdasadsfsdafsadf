package app.obdforge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.obdforge.data.ThemeMode

object Palette {
    val Cyan = Color(0xFF22D3EE)
    val Orange = Color(0xFFF97316)
    val Green = Color(0xFF4ADE80)
    val Red = Color(0xFFEF4444)
    val Amber = Color(0xFFFACC15)
    val Purple = Color(0xFFC084FC)
    val series = listOf(Cyan, Orange, Green, Purple, Amber, Color(0xFFF472B6), Color(0xFF60A5FA), Color(0xFFA3E635))
}

private val Dark = darkColorScheme(
    primary = Palette.Cyan, onPrimary = Color(0xFF00242B),
    secondary = Palette.Orange, onSecondary = Color(0xFF2B1300),
    tertiary = Palette.Green,
    background = Color(0xFF0B0F14), onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF111821), onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1A2330), onSurfaceVariant = Color(0xFF9FB0C3),
    surfaceContainer = Color(0xFF141C27), surfaceContainerHigh = Color(0xFF1A2330), surfaceContainerHighest = Color(0xFF212B3A),
    surfaceContainerLow = Color(0xFF0F151D), surfaceContainerLowest = Color(0xFF0B0F14),
    outline = Color(0xFF2E3B4E), outlineVariant = Color(0xFF243042),
    error = Palette.Red,
)

private val Light = lightColorScheme(
    primary = Color(0xFF0891B2), secondary = Color(0xFFEA580C), tertiary = Color(0xFF16A34A),
    background = Color(0xFFF6F8FA), surface = Color.White, surfaceVariant = Color(0xFFE8EDF2),
    onSurfaceVariant = Color(0xFF4B5B6B), error = Color(0xFFDC2626),
)

private val Mono = FontFamily.Monospace

val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 52.sp),
    displayMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 40.sp),
    displaySmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp),
)

@Composable
fun ObdTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, content = content)
}
