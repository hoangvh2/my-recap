package com.vh.myrecap.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vh.myrecap.R

/** Colours taken from the app icon; used where the brand should show regardless of light/dark. */
object Brand {
    val Navy = Color(0xFF0A1640)
    val NavyDeep = Color(0xFF060C26)
    val NavyRaised = Color(0xFF141F52)
    val Indigo = Color(0xFF4353F0)
    val Cyan = Color(0xFF2EC5E6)
    val Violet = Color(0xFF9B6CF7)
    val Record = Color(0xFFE5484D)
    val Bookmark = Color(0xFFF5A524)
}

val BeVietnamPro = FontFamily(
    Font(R.font.be_vietnam_pro_regular, FontWeight.Normal),
    Font(R.font.be_vietnam_pro_medium, FontWeight.Medium),
    Font(R.font.be_vietnam_pro_semibold, FontWeight.SemiBold),
    Font(R.font.be_vietnam_pro_bold, FontWeight.Bold),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3443D9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E5FF),
    onPrimaryContainer = Color(0xFF0D145C),
    secondary = Color(0xFF0B87A6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5F2F9),
    onSecondaryContainer = Color(0xFF00313C),
    tertiary = Color(0xFF7446E0),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE5FF),
    onTertiaryContainer = Color(0xFF270B61),
    error = Color(0xFFD13A40),
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E3),
    onErrorContainer = Color(0xFF5A0B0E),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF12142A),
    surface = Color(0xFFF6F7FB),
    onSurface = Color(0xFF12142A),
    surfaceVariant = Color(0xFFE8EAF3),
    onSurfaceVariant = Color(0xFF585D77),
    outline = Color(0xFFB4B8CC),
    outlineVariant = Color(0xFFDDE0EC),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0F2F8),
    surfaceContainerHigh = Color(0xFFEAECF5),
    surfaceContainerHighest = Color(0xFFE2E5F0),
    inverseSurface = Color(0xFF242842),
    inverseOnSurface = Color(0xFFF1F2FA),
    inversePrimary = Color(0xFFB9C0FF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA7B0FF),
    onPrimary = Color(0xFF0B1466),
    primaryContainer = Color(0xFF29349F),
    onPrimaryContainer = Color(0xFFE2E5FF),
    secondary = Color(0xFF72D6EC),
    onSecondary = Color(0xFF00363F),
    secondaryContainer = Color(0xFF004E5D),
    onSecondaryContainer = Color(0xFFD5F2F9),
    tertiary = Color(0xFFCDB8FF),
    onTertiary = Color(0xFF3B1A85),
    tertiaryContainer = Color(0xFF55309F),
    onTertiaryContainer = Color(0xFFEDE5FF),
    error = Color(0xFFFF8F92),
    onError = Color(0xFF5A0B0E),
    errorContainer = Color(0xFF7F1D22),
    onErrorContainer = Color(0xFFFFE4E3),
    background = Color(0xFF0A0E1F),
    onBackground = Color(0xFFE6E8F4),
    surface = Color(0xFF0A0E1F),
    onSurface = Color(0xFFE6E8F4),
    surfaceVariant = Color(0xFF262B45),
    onSurfaceVariant = Color(0xFFA8ADC8),
    outline = Color(0xFF4C5272),
    outlineVariant = Color(0xFF2B3050),
    surfaceContainerLowest = Color(0xFF070A17),
    surfaceContainerLow = Color(0xFF12172F),
    surfaceContainer = Color(0xFF161B36),
    surfaceContainerHigh = Color(0xFF1D2343),
    surfaceContainerHighest = Color(0xFF252B4E),
    inverseSurface = Color(0xFFE6E8F4),
    inverseOnSurface = Color(0xFF1A1E36),
    inversePrimary = Color(0xFF3443D9),
)

private fun TextStyle.brand() = copy(fontFamily = BeVietnamPro)

private val AppTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.brand(),
        displayMedium = t.displayMedium.brand(),
        displaySmall = t.displaySmall.brand(),
        headlineLarge = t.headlineLarge.brand().copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = t.headlineMedium.brand().copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.brand().copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
        titleLarge = t.titleLarge.brand().copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
        titleMedium = t.titleMedium.brand().copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
        titleSmall = t.titleSmall.brand().copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
        bodyLarge = t.bodyLarge.brand().copy(fontSize = 16.sp, lineHeight = 26.sp, letterSpacing = 0.1.sp),
        bodyMedium = t.bodyMedium.brand().copy(fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.1.sp),
        bodySmall = t.bodySmall.brand().copy(fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = t.labelLarge.brand().copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
        labelMedium = t.labelMedium.brand().copy(fontWeight = FontWeight.Medium, fontSize = 12.sp),
        labelSmall = t.labelSmall.brand().copy(fontWeight = FontWeight.Medium, fontSize = 11.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Fixed brand palette (no wallpaper-based dynamic colour) so the app looks the same on every
 * phone, in light and dark mode.
 */
@Composable
fun MyRecapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
