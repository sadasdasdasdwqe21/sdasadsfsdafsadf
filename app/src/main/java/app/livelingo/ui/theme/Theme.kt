package app.livelingo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Brand {
    val Background = Color(0xFF0B0F17)
    val Surface = Color(0xFF121826)
    val SurfaceHigh = Color(0xFF1A2233)
    val SurfaceHighest = Color(0xFF232D42)
    val Teal = Color(0xFF4FD1C5)
    val TealDim = Color(0xFF2C8C84)
    val Violet = Color(0xFF8B7CF6)
    val Amber = Color(0xFFF6C35B)
    val Red = Color(0xFFFF6B6B)
    val Green = Color(0xFF4ADE80)
    val TextPrimary = Color(0xFFE8ECF4)
    val TextSecondary = Color(0xFF9AA4B8)
    val Outline = Color(0xFF2A3348)
}

private val colors = darkColorScheme(
    primary = Brand.Teal,
    onPrimary = Color(0xFF00201D),
    primaryContainer = Color(0xFF0F3B38),
    onPrimaryContainer = Color(0xFFB9F3EC),
    secondary = Brand.Violet,
    onSecondary = Color(0xFF1B1240),
    secondaryContainer = Color(0xFF2A2357),
    onSecondaryContainer = Color(0xFFE2DDFF),
    tertiary = Brand.Amber,
    background = Brand.Background,
    onBackground = Brand.TextPrimary,
    surface = Brand.Surface,
    onSurface = Brand.TextPrimary,
    surfaceVariant = Brand.SurfaceHigh,
    onSurfaceVariant = Brand.TextSecondary,
    surfaceContainer = Brand.Surface,
    surfaceContainerHigh = Brand.SurfaceHigh,
    surfaceContainerHighest = Brand.SurfaceHighest,
    surfaceContainerLow = Color(0xFF0F1420),
    outline = Brand.Outline,
    outlineVariant = Color(0xFF222A3C),
    error = Brand.Red,
)

private val type = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
)

private val shapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun LiveLingoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = type, shapes = shapes, content = content)
}
