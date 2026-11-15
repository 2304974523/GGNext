package com.littlecodesauce.ggnext.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

/** 主题色预设 */
enum class ThemePreset(val label: String, val seed: Color) {
    ROSE("玫瑰", Color(0xFFE7A8B8)),
    GREEN("青翠", Color(0xFF7FD99A)),
    CYAN("天青", Color(0xFF8FD6DE)),
    BLUE("蔚蓝", Color(0xFF8AA9E8)),
    VIOLET("紫罗兰", Color(0xFFC79AE0)),
    AMBER("琥珀", Color(0xFFE8C07F)),
    RED("赤红", Color(0xFFE88A8A)),
    MONO("中性", Color(0xFFC9C4C6))
}

enum class DarkMode(val label: String) { FOLLOW("跟随系统"), DARK("深色"), LIGHT("浅色") }

/**
 * 动态计算全套 UI 语义色板：
 * 界面调整 10 & Bug 1：
 * 深度优化浅色模式（isDark == false）的配色层次与字体对比度，
 * 采用纯净白/浅暖灰卡片底色与高对比深炭黑字体（#121217 / #34343E / #585866），
 * 确保浅色模式下所有标题、副标题、滑块标签、按钮与地址数值清晰锐利。
 */
data class AppPalette(
    val isDark: Boolean,
    val scrim: Color,
    val surface: Color,
    val card: Color,
    val cardHigh: Color,
    val row: Color,
    val primary: Color,
    val primaryDeep: Color,
    val primarySoft: Color,
    val onPrimary: Color,
    val chip: Color,
    val chipSelected: Color,
    val text: Color,
    val textSecond: Color,
    val textDim: Color,
    val divider: Color
)

fun buildAppPalette(seed: Color, dark: Boolean): AppPalette {
    return if (dark) {
        val baseDark = Color(0xFF121013)
        val surface = lerp(baseDark, seed, 0.04f)
        val row = lerp(Color(0xFF1D1A1E), seed, 0.07f)
        val card = lerp(Color(0xFF211D22), seed, 0.08f)
        val cardHigh = lerp(Color(0xFF2B252C), seed, 0.11f)
        val chip = lerp(Color(0xFF332C34), seed, 0.14f)
        val chipSelected = lerp(Color(0xFF3E3540), seed, 0.38f)
        val primaryDeep = lerp(Color(0xFF2A2228), seed, 0.52f)
        AppPalette(
            isDark = true,
            scrim = Color(0xCC09070A),
            surface = surface,
            card = card,
            cardHigh = cardHigh,
            row = row,
            primary = seed,
            primaryDeep = primaryDeep,
            primarySoft = seed.copy(alpha = 0.22f),
            onPrimary = Color(0xFF1A1216),
            chip = chip,
            chipSelected = chipSelected,
            text = Color(0xFFF3EDF0),
            textSecond = lerp(Color(0xFFB8B0B4), seed, 0.16f),
            textDim = lerp(Color(0xFF857C81), seed, 0.12f),
            divider = Color(0x24F3EDF0)
        )
    } else {
        // 界面调整 10：浅色模式采用高对比深墨黑字体与清晰分层的浅色表面
        val accentStrong = lerp(seed, Color(0xFF160E14), 0.46f)
        val surface = lerp(Color(0xFFF3F4F8), seed, 0.06f)
        val card = lerp(Color(0xFFFFFFFF), seed, 0.02f)
        val row = lerp(Color(0xFFECEEF4), seed, 0.08f)
        val cardHigh = lerp(Color(0xFFE2E4ED), seed, 0.11f)
        val chip = lerp(Color(0xFFD7DAE6), seed, 0.15f)
        val chipSelected = lerp(Color(0xFFC4C8D8), seed, 0.38f)
        val primaryDeep = lerp(Color(0xFFB4B8CA), seed, 0.45f)
        AppPalette(
            isDark = false,
            scrim = Color(0x66121016),
            surface = surface,
            card = card,
            cardHigh = cardHigh,
            row = row,
            primary = accentStrong,
            primaryDeep = primaryDeep,
            primarySoft = accentStrong.copy(alpha = 0.15f),
            onPrimary = Color(0xFFFFFFFF),
            chip = chip,
            chipSelected = chipSelected,
            text = Color(0xFF121217),
            textSecond = Color(0xFF34343E),
            textDim = Color(0xFF585866),
            divider = Color(0x26121217)
        )
    }
}

fun presetScheme(palette: AppPalette): ColorScheme {
    return if (palette.isDark) {
        darkColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            primaryContainer = palette.chipSelected,
            onPrimaryContainer = palette.text,
            secondary = palette.primary,
            background = palette.surface,
            onBackground = palette.text,
            surface = palette.card,
            onSurface = palette.text,
            surfaceVariant = palette.cardHigh,
            onSurfaceVariant = palette.textSecond,
            outline = palette.divider,
            error = Color(0xFFE87F8A)
        )
    } else {
        lightColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            primaryContainer = palette.chipSelected,
            onPrimaryContainer = palette.text,
            secondary = palette.primary,
            background = palette.surface,
            onBackground = palette.text,
            surface = palette.card,
            onSurface = palette.text,
            surfaceVariant = palette.cardHigh,
            onSurfaceVariant = palette.textSecond,
            outline = palette.divider,
            error = Color(0xFFB3261E)
        )
    }
}

@Composable
fun rememberPaletteAndScheme(
    preset: ThemePreset,
    darkMode: DarkMode,
    dynamicColor: Boolean,
    systemDark: Boolean
): Pair<AppPalette, ColorScheme> {
    val dark = when (darkMode) {
        DarkMode.DARK -> true
        DarkMode.LIGHT -> false
        DarkMode.FOLLOW -> systemDark
    }
    val ctx = LocalContext.current
    if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dynScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        val pal = buildAppPalette(dynScheme.primary, dark)
        return pal to dynScheme
    }
    val pal = buildAppPalette(preset.seed, dark)
    return pal to presetScheme(pal)
}
