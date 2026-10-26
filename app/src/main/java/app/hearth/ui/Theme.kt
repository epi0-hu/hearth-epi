package app.hearth.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/** Colors used by the message formatter (dialogue, actions, code). */
@Immutable
data class ChatPalette(
    val quote: Color,
    val action: Color,
    val code: Color,
    val codeBg: Color,
    val userBubble: Color,
    val botBubble: Color,
    val ember: Color = Color(0xFFFF8A3D),
)

val LocalChatPalette = staticCompositionLocalOf {
    ChatPalette(Color.White, Color.Gray, Color.White, Color.DarkGray, Color.DarkGray, Color.Black)
}

private val Ember = Color(0xFFE8834A)
private val EmberDeep = Color(0xFFB85A2B)

private val DarkScheme = darkColorScheme(
    primary = Ember,
    onPrimary = Color(0xFF2A1408),
    primaryContainer = Color(0xFF5A2E16),
    onPrimaryContainer = Color(0xFFFFDBC9),
    secondary = Color(0xFFD9B99B),
    onSecondary = Color(0xFF2B1E12),
    secondaryContainer = Color(0xFF3B2F27),
    onSecondaryContainer = Color(0xFFF1DFD1),
    tertiary = Color(0xFF9FC8A0),
    background = Color(0xFF0F0B09),
    onBackground = Color(0xFFEDE0D8),
    surface = Color(0xFF14100D),
    onSurface = Color(0xFFEDE0D8),
    surfaceVariant = Color(0xFF2B2420),
    onSurfaceVariant = Color(0xFFCDBDB2),
    surfaceContainerLowest = Color(0xFF0F0C0B),
    surfaceContainerLow = Color(0xFF1C1816),
    surfaceContainer = Color(0xFF211C19),
    surfaceContainerHigh = Color(0xFF2B2522),
    surfaceContainerHighest = Color(0xFF36302C),
    outline = Color(0xFF6E615A),
    outlineVariant = Color(0xFF3F3631),
    error = Color(0xFFFFB4A9),
)

private val LightScheme = lightColorScheme(
    primary = EmberDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBC9),
    onPrimaryContainer = Color(0xFF3A1A08),
    secondary = Color(0xFF75594A),
    secondaryContainer = Color(0xFFF6E3D8),
    background = Color(0xFFFFF8F4),
    onBackground = Color(0xFF221A16),
    surface = Color(0xFFFFF8F4),
    onSurface = Color(0xFF221A16),
    surfaceVariant = Color(0xFFF3E4DA),
    onSurfaceVariant = Color(0xFF53443B),
    surfaceContainerLow = Color(0xFFFBF1EB),
    surfaceContainer = Color(0xFFF6EBE4),
    surfaceContainerHigh = Color(0xFFF0E4DC),
    surfaceContainerHighest = Color(0xFFEADDD4),
    outline = Color(0xFF85736A),
    outlineVariant = Color(0xFFD8C3B7),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        headlineMedium = t.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    )
}

@Composable
fun HearthTheme(fontScale: Float, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) DarkScheme else LightScheme
    val palette = if (dark) {
        ChatPalette(
            quote = Color(0xFFF2C58F), action = Color(0xFFB8A99F), code = Color(0xFFE6D5C8),
            codeBg = Color(0xFF2A2420), userBubble = Color(0xFF2E2622), botBubble = Color.Transparent,
            ember = Color(0xFFFF8A3D),
        )
    } else {
        ChatPalette(
            quote = Color(0xFF9A4A12), action = Color(0xFF6B5D55), code = Color(0xFF3A2C24),
            codeBg = Color(0xFFF0E4DC), userBubble = Color(0xFFF3E4DA), botBubble = Color.Transparent,
            ember = Color(0xFFE0651E),
        )
    }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalChatPalette provides palette,
        LocalDensity provides Density(density.density, density.fontScale * fontScale),
    ) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}

/** Text style for chat prose: a reading serif with generous line height. */
fun proseStyle(serif: Boolean): TextStyle = TextStyle(
    fontFamily = if (serif) FontFamily.Serif else FontFamily.Default,
    fontSize = 17.sp,
    lineHeight = 27.sp,
)
