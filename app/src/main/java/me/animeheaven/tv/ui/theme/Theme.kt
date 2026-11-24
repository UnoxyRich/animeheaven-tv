package me.animeheaven.tv.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import me.animeheaven.tv.data.model.AhThemeId

@Immutable
data class AhThemePalette(
    val themeId: AhThemeId,
    val background: Color,
    val backgroundBrush: Brush?,
    val swatchBrush: Brush?,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceFocused: Color,
    val crimson: Color,
    val crimsonDark: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val borderSubtle: Color,
    val positiveGreen: Color,
    val focusBorder: Color,
    val focusContainer: Color,
    val onFocusContainer: Color,
    val chipContainer: Color,
    val chipBorder: Color,
    val isLight: Boolean
)

private val CACHED_PALETTES: Array<AhThemePalette> by lazy {
    Array(AhThemeId.entries.size) { idx -> buildPaletteFor(AhThemeId.entries[idx]) }
}

private val CACHED_COLOR_SCHEMES: Array<ColorScheme> by lazy {
    Array(AhThemeId.entries.size) { idx ->
        val p = CACHED_PALETTES[idx]
        if (p.isLight) {
            lightColorScheme(
                primary = p.crimson,
                onPrimary = Color.White,
                background = p.background,
                onBackground = p.textPrimary,
                surface = p.surface,
                onSurface = p.textPrimary
            )
        } else {
            darkColorScheme(
                primary = p.crimson,
                onPrimary = Color.White,
                background = p.background,
                onBackground = p.textPrimary,
                surface = p.surface,
                onSurface = p.textPrimary
            )
        }
    }
}

val LocalAhPalette = staticCompositionLocalOf { paletteFor(AhThemeId.DARK) }

fun paletteFor(themeId: AhThemeId): AhThemePalette = CACHED_PALETTES[themeId.ordinal]

private fun buildPaletteFor(themeId: AhThemeId): AhThemePalette {
    val crimson = Color(0xFFE50914) // Signature Netflix Red
    val crimsonDark = Color(0xFFB20710)
    val green = Color(0xFF46D369) // Netflix Match Green

    return when (themeId) {
        AhThemeId.DARK -> AhThemePalette(
            themeId = themeId,
            background = Color(0xFF0B0B0B),
            backgroundBrush = Brush.verticalGradient(
                colors = listOf(Color(0xFF1A090B), Color(0xFF0B0B0B), Color(0xFF0B0B0B))
            ),
            swatchBrush = Brush.linearGradient(
                colors = listOf(Color(0xFF280A0E), Color(0xFF0B0B0B)),
                start = Offset.Zero,
                end = Offset.Infinite
            ),
            surface = Color(0xFF141414),
            surfaceElevated = Color(0xFF1F1F1F),
            surfaceFocused = Color(0xFF2B2B2B),
            crimson = crimson,
            crimsonDark = crimsonDark,
            textPrimary = Color(0xFFFFFFFF),
            textSecondary = Color(0xFFD2D2D2),
            textMuted = Color(0xFF8C8C8C),
            borderSubtle = Color(0xFF2A2A2A),
            positiveGreen = green,
            focusBorder = Color.White,
            focusContainer = Color.White,
            onFocusContainer = Color.Black,
            chipContainer = Color(0x26FFFFFF),
            chipBorder = Color(0x38FFFFFF),
            isLight = false
        )
        AhThemeId.DARK_INDIGO -> gradientDarkPalette(
            themeId, Color(0xFF2C1231), Color(0xFF052031), crimson, crimsonDark, green
        )
        AhThemeId.DARK_GRAPHITE -> gradientDarkPalette(
            themeId, Color(0xFF232526), Color(0xFF414345), crimson, crimsonDark, green
        )
        AhThemeId.DARK_TEAL_PLUM -> gradientDarkPalette(
            themeId, Color(0xFF01534E), Color(0xFF5A1C4C), crimson, crimsonDark, green
        )
        AhThemeId.DARK_CRIMSON_VELVET -> gradientDarkPalette(
            themeId, Color(0xFF800000), Color(0xFF5A1C4C), crimson, crimsonDark, green
        )
        AhThemeId.DARK_MAGENTA -> gradientDarkPalette(
            themeId, Color(0xFFAA076B), Color(0xFF61045F), crimson, crimsonDark, green
        )
        AhThemeId.DARK_TWILIGHT -> gradientDarkPalette(
            themeId, Color(0xFF536976), Color(0xFF292E49), crimson, crimsonDark, green
        )
        AhThemeId.LIGHT -> AhThemePalette(
            themeId = themeId,
            background = Color(0xFFFFFFFF),
            backgroundBrush = null,
            swatchBrush = null,
            surface = Color(0xFFF3F3F8),
            surfaceElevated = Color(0xFFE7E7EE),
            surfaceFocused = Color(0xFFE0E0EA),
            crimson = crimson,
            crimsonDark = crimsonDark,
            textPrimary = Color(0xFF1A1A1A),
            textSecondary = Color(0xFF333333),
            textMuted = Color(0xFF666666),
            borderSubtle = Color(0xFFD0D0DC),
            positiveGreen = Color(0xFF158A4A),
            focusBorder = Color(0xFF1A1A1A),
            focusContainer = Color(0xFF1A1A1A),
            onFocusContainer = Color.White,
            chipContainer = Color(0x18000000),
            chipBorder = Color(0x30000000),
            isLight = true
        )
    }
}

private fun gradientDarkPalette(
    themeId: AhThemeId,
    start: Color,
    end: Color,
    crimson: Color,
    crimsonDark: Color,
    green: Color
): AhThemePalette = AhThemePalette(
    themeId = themeId,
    background = end,
    backgroundBrush = Brush.linearGradient(
        colors = listOf(start, end),
        start = Offset.Zero,
        end = Offset(1920f, 300f)
    ),
    swatchBrush = Brush.linearGradient(
        colors = listOf(start, end),
        start = Offset.Zero,
        end = Offset.Infinite
    ),
    surface = Color(0x99000000),
    surfaceElevated = Color(0xBF0E0E0E),
    surfaceFocused = Color(0xE01A1A1A),
    crimson = crimson,
    crimsonDark = crimsonDark,
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFCFCFCF),
    textMuted = Color(0xFF9E9E9E),
    borderSubtle = Color(0x44FFFFFF),
    positiveGreen = green,
    focusBorder = Color.White,
    focusContainer = Color.White,
    onFocusContainer = Color.Black,
    chipContainer = Color(0x26FFFFFF),
    chipBorder = Color(0x38FFFFFF),
    isLight = false
)

private val AhTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold
    ),
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal
    ),
    labelLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold
    ),
    labelMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium
    )
)

@OptIn(ExperimentalFoundationApi::class)
private val AhSmoothBringIntoViewSpec = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(
        durationMillis = 260,
        easing = CubicBezierEasing(0.18f, 0.88f, 0.32f, 1.0f)
    )

    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float
    ): Float {
        if (containerSize <= 0f) return 0f
        val childSize = abs(size)
        val childSmallerThanParent = childSize <= containerSize
        val initialTarget = containerSize * 0.24f
        val spaceAvailable = containerSize - initialTarget
        val targetLeading = if (childSmallerThanParent && spaceAvailable < childSize) {
            containerSize - childSize
        } else {
            initialTarget
        }
        return offset - targetLeading
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnimeHeavenTheme(
    themeId: AhThemeId = AhThemeId.DARK,
    content: @Composable () -> Unit
) {
    val palette = CACHED_PALETTES[themeId.ordinal]
    val colorScheme = CACHED_COLOR_SCHEMES[themeId.ordinal]

    CompositionLocalProvider(
        LocalAhPalette provides palette,
        LocalBringIntoViewSpec provides AhSmoothBringIntoViewSpec
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AhTypography
        ) {
            val bgModifier = if (palette.backgroundBrush != null) {
                Modifier.fillMaxSize().background(palette.backgroundBrush)
            } else {
                Modifier.fillMaxSize().background(palette.background)
            }
            Box(modifier = bgModifier) {
                content()
            }
        }
    }
}
