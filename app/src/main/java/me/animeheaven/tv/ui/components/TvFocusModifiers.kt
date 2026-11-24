package me.animeheaven.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.animeheaven.tv.ui.theme.LocalAhPalette

val AhOverscanHorizontal: Dp = 40.dp
val AhOverscanVertical: Dp = 18.dp

// Hoisted common shapes eliminate per-recomposition RoundedCornerShape allocations
val AhShapeRect: Shape = RectangleShape
val AhShape2: Shape = RoundedCornerShape(4.dp)
val AhShape3: Shape = RoundedCornerShape(6.dp)
val AhShape4: Shape = RoundedCornerShape(8.dp)
val AhShape8: Shape = RoundedCornerShape(8.dp)
val AhShape10: Shape = RoundedCornerShape(10.dp)
val AhShape12: Shape = RoundedCornerShape(12.dp)
val AhShape16: Shape = RoundedCornerShape(16.dp)
val AhShape20: Shape = RoundedCornerShape(24.dp)

private val FOCUS_SCALE_SPEC = tween<Float>(durationMillis = 120)

/**
 * High-performance TV focus container:
 * - Zero idle animation coroutines on unfocused cards/keys (Animatable allocated lazily on first focus)
 * - Background & border drawn cleanly in the Draw phase so theme/selection changes ALWAYS redraw immediately
 * - Shape clipping & focus scale execute on a dedicated graphicsLayer wrapping both background/border and content
 */
@Composable
fun AhFocusableBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = AhShape8,
    containerColor: Color? = null,
    focusedContainerColor: Color? = null,
    unfocusedBorderColor: Color? = null,
    focusedBorderColor: Color? = null,
    borderWidth: Dp = 1.dp,
    focusedBorderWidth: Dp = 2.5.dp,
    focusScale: Float = 1.05f,
    onFocusStateChanged: ((Boolean) -> Unit)? = null,
    content: @Composable BoxScope.(isFocused: Boolean) -> Unit
) {
    val palette = LocalAhPalette.current
    val resolvedContainer = containerColor ?: palette.surface
    val resolvedFocusedContainer = focusedContainerColor ?: palette.surfaceFocused
    val resolvedUnfocusedBorder = unfocusedBorderColor ?: Color.Unspecified
    val resolvedFocusedBorder = focusedBorderColor ?: palette.focusBorder

    var isFocused by remember { mutableStateOf(false) }
    val scaleState = remember { mutableFloatStateOf(1f) }
    var hasEverAnimated by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val targetScale = if (isFocused && enabled) focusScale else 1f
    if (targetScale != 1f || hasEverAnimated) {
        LaunchedEffect(targetScale) {
            hasEverAnimated = true
            val anim = Animatable(scaleState.floatValue)
            anim.animateTo(targetScale, animationSpec = FOCUS_SCALE_SPEC) {
                scaleState.floatValue = value
            }
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                val s = scaleState.floatValue
                scaleX = s
                scaleY = s
                if (shape !== RectangleShape) {
                    this.shape = shape
                    clip = true
                }
            }
            .drawWithContent {
                drawAhFocusChrome(
                    activeFocus = isFocused && enabled,
                    shape = shape,
                    containerColor = resolvedContainer,
                    focusedContainerColor = resolvedFocusedContainer,
                    unfocusedBorderColor = resolvedUnfocusedBorder,
                    focusedBorderColor = resolvedFocusedBorder,
                    borderWidth = borderWidth,
                    focusedBorderWidth = focusedBorderWidth
                )
            }
            .onFocusChanged { focusState ->
                val now = focusState.isFocused
                if (isFocused != now) {
                    isFocused = now
                    onFocusStateChanged?.invoke(now)
                }
            }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        content(isFocused)
    }
}

/**
 * Zero-recomposition TV focus container for items whose child content does NOT depend on
 * `isFocused` (cards with static thumbnails, icon buttons, arrow buttons, etc.). Focus transitions
 * execute purely in the GraphicsLayer & Draw phases with zero child content recomposition, while
 * theme/selection color & border updates always redraw immediately.
 */
@Composable
fun AhStaticFocusableBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = AhShape8,
    containerColor: Color? = null,
    focusedContainerColor: Color? = null,
    unfocusedBorderColor: Color? = null,
    focusedBorderColor: Color? = null,
    borderWidth: Dp = 1.dp,
    focusedBorderWidth: Dp = 2.5.dp,
    focusScale: Float = 1.05f,
    onFocusStateChanged: ((Boolean) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val palette = LocalAhPalette.current
    val resolvedContainer = containerColor ?: palette.surface
    val resolvedFocusedContainer = focusedContainerColor ?: palette.surfaceFocused
    val resolvedUnfocusedBorder = unfocusedBorderColor ?: Color.Unspecified
    val resolvedFocusedBorder = focusedBorderColor ?: palette.focusBorder

    val isFocusedState = remember { mutableStateOf(false) }
    val scaleState = remember { mutableFloatStateOf(1f) }
    val animRef = remember { arrayOfNulls<Animatable<Float, AnimationVector1D>>(1) }
    val jobRef = remember { arrayOfNulls<Job>(1) }
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }

    LaunchedEffect(enabled, focusScale) {
        val target = if (isFocusedState.value && enabled) focusScale else 1f
        if (target != scaleState.floatValue) {
            jobRef[0]?.cancel()
            val anim = animRef[0] ?: Animatable(scaleState.floatValue).also { animRef[0] = it }
            anim.animateTo(target, animationSpec = FOCUS_SCALE_SPEC) {
                scaleState.floatValue = value
            }
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                val s = scaleState.floatValue
                scaleX = s
                scaleY = s
                if (shape !== RectangleShape) {
                    this.shape = shape
                    clip = true
                }
            }
            .drawWithContent {
                drawAhFocusChrome(
                    activeFocus = isFocusedState.value && enabled,
                    shape = shape,
                    containerColor = resolvedContainer,
                    focusedContainerColor = resolvedFocusedContainer,
                    unfocusedBorderColor = resolvedUnfocusedBorder,
                    focusedBorderColor = resolvedFocusedBorder,
                    borderWidth = borderWidth,
                    focusedBorderWidth = focusedBorderWidth
                )
            }
            .onFocusChanged { focusState ->
                val now = focusState.isFocused
                if (isFocusedState.value != now) {
                    isFocusedState.value = now
                    val target = if (now && enabled) focusScale else 1f
                    jobRef[0]?.cancel()
                    jobRef[0] = scope.launch {
                        val anim = animRef[0] ?: Animatable(scaleState.floatValue).also { animRef[0] = it }
                        anim.animateTo(target, animationSpec = FOCUS_SCALE_SPEC) {
                            scaleState.floatValue = value
                        }
                    }
                    onFocusStateChanged?.invoke(now)
                }
            }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        content = content
    )
}

private fun ContentDrawScope.drawAhFocusChrome(
    activeFocus: Boolean,
    shape: Shape,
    containerColor: Color,
    focusedContainerColor: Color,
    unfocusedBorderColor: Color,
    focusedBorderColor: Color,
    borderWidth: Dp,
    focusedBorderWidth: Dp
) {
    val bg = if (activeFocus) focusedContainerColor else containerColor
    val cachedOutline = if (shape !== RectangleShape && shape !== CircleShape) {
        shape.createOutline(size, layoutDirection, this)
    } else {
        null
    }

    if (bg != Color.Transparent && bg != Color.Unspecified) {
        when (shape) {
            RectangleShape -> drawRect(color = bg)
            CircleShape -> drawCircle(color = bg, radius = size.minDimension / 2f)
            else -> if (cachedOutline != null) {
                drawOutline(outline = cachedOutline, color = bg)
            }
        }
    }

    drawContent()

    val borderColor = if (activeFocus) focusedBorderColor else unfocusedBorderColor
    if (borderColor != Color.Unspecified && borderColor != Color.Transparent) {
        val strokePx = (if (activeFocus) focusedBorderWidth else borderWidth).toPx()
        if (strokePx > 0f) {
            when (shape) {
                RectangleShape -> {
                    val half = strokePx / 2f
                    drawRect(
                        color = borderColor,
                        topLeft = Offset(half, half),
                        size = Size(
                            (size.width - strokePx).coerceAtLeast(0f),
                            (size.height - strokePx).coerceAtLeast(0f)
                        ),
                        style = Stroke(width = strokePx)
                    )
                }
                CircleShape -> {
                    val radius = ((size.minDimension - strokePx) / 2f).coerceAtLeast(0f)
                    drawCircle(
                        color = borderColor,
                        radius = radius,
                        style = Stroke(width = strokePx)
                    )
                }
                else -> if (cachedOutline != null) {
                    val clipOutlinePath = Path().apply { addOutline(cachedOutline) }
                    clipPath(clipOutlinePath) {
                        drawOutline(
                            outline = cachedOutline,
                            color = borderColor,
                            style = Stroke(width = strokePx * 2f)
                        )
                    }
                }
            }
        }
    }
}
