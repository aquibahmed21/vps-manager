package com.aquib.vpshost

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Good = Color(0xFF16A34A)
val Warn = Color(0xFFD97706)
val Bad = Color(0xFFDC2626)

private val Teal = Color(0xFF0F766E)
private val LightColors = lightColorScheme(
    primary = Teal, onPrimary = Color.White, secondary = Color(0xFF475569),
    background = Color(0xFFF4F7F7), surface = Color(0xFFF4F7F7),
    surfaceContainerLow = Color.White, surfaceContainer = Color(0xFFE9EFEF),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF5EEAD4), onPrimary = Color(0xFF042F2E),
    background = Color(0xFF0D1413), surface = Color(0xFF0D1413),
    surfaceContainerLow = Color(0xFF152120), surfaceContainer = Color(0xFF1B2B2A),
)

private fun compactTypography(): Typography {
    val t = Typography()
    return t.copy(
        headlineSmall = t.headlineSmall.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        titleSmall = t.titleSmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        bodyLarge = t.bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
        bodySmall = t.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = t.labelLarge.copy(fontSize = 13.sp),
        labelMedium = t.labelMedium.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun VpsTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, typography = compactTypography(), content = content)
