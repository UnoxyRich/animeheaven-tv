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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
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
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateLayer
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
 * High-performance TV focus container backed by a custom [Modifier.Node]:
 * - Zero idle animation coroutines on unfocused cards/keys (Animatable is allocated lazily on first focus)
 * - Background & border are drawn in the Draw phase (zero BorderStroke allocations, zero relayout)
 * - Shape clipping is merged into the graphics layer (no redundant .clip() render node)
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
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .then(
                AhTvFocusModifierElement(
                    enabled = enabled,
                    shape = shape,
                    containerColor = containerColor ?: palette.surface,
                    focusedContainerColor = focusedContainerColor ?: palette.surfaceFocused,
                    unfocusedBorderColor = unfocusedBorderColor ?: Color.Unspecified,
                    focusedBorderColor = focusedBorderColor ?: Color.White,
                    borderWidth = borderWidth,
                    focusedBorderWidth = focusedBorderWidth,
                    focusScale = focusScale,
                    onFocusChanged = { now ->
                        if (isFocused != now) {
                            isFocused = now
                            onFocusStateChanged?.invoke(now)
                        }
                    }
                )
            )
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
 * execute purely in the GraphicsLayer & Draw phases with zero Compose recomposition.
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
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .then(
                AhTvFocusModifierElement(
                    enabled = enabled,
                    shape = shape,
                    containerColor = containerColor ?: palette.surface,
                    focusedContainerColor = focusedContainerColor ?: palette.surfaceFocused,
                    unfocusedBorderColor = unfocusedBorderColor ?: Color.Unspecified,
                    focusedBorderColor = focusedBorderColor ?: Color.White,
                    borderWidth = borderWidth,
                    focusedBorderWidth = focusedBorderWidth,
                    focusScale = focusScale,
                    onFocusChanged = onFocusStateChanged
                )
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        content = content
    )
}

private data class AhTvFocusModifierElement(
    val enabled: Boolean,
    val shape: Shape,
    val containerColor: Color,
    val focusedContainerColor: Color,
    val unfocusedBorderColor: Color,
    val focusedBorderColor: Color,
    val borderWidth: Dp,
    val focusedBorderWidth: Dp,
    val focusScale: Float,
    val onFocusChanged: ((Boolean) -> Unit)?
) : ModifierNodeElement<AhTvFocusNode>() {

    override fun create(): AhTvFocusNode = AhTvFocusNode(
        enabled = enabled,
        shape = shape,
        containerColor = containerColor,
        focusedContainerColor = focusedContainerColor,
        unfocusedBorderColor = unfocusedBorderColor,
        focusedBorderColor = focusedBorderColor,
        borderWidth = borderWidth,
        focusedBorderWidth = focusedBorderWidth,
        focusScale = focusScale,
        onFocusChanged = onFocusChanged
    )

    override fun update(node: AhTvFocusNode) {
        val visualChanged = node.enabled != enabled ||
            node.shape != shape ||
            node.containerColor != containerColor ||
            node.focusedContainerColor != focusedContainerColor ||
            node.unfocusedBorderColor != unfocusedBorderColor ||
            node.focusedBorderColor != focusedBorderColor ||
            node.borderWidth != borderWidth ||
            node.focusedBorderWidth != focusedBorderWidth

        val scaleTargetChanged = node.enabled != enabled || node.focusScale != focusScale

        node.enabled = enabled
        node.shape = shape
        node.containerColor = containerColor
        node.focusedContainerColor = focusedContainerColor
        node.unfocusedBorderColor = unfocusedBorderColor
        node.focusedBorderColor = focusedBorderColor
        node.borderWidth = borderWidth
        node.focusedBorderWidth = focusedBorderWidth
        node.focusScale = focusScale
        node.onFocusChanged = onFocusChanged

        if (scaleTargetChanged) {
            node.syncScaleTarget()
        }
        if (visualChanged) {
            node.invalidateDraw()
            node.invalidateLayer()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "ahTvFocus"
    }
}

private class AhTvFocusNode(
    var enabled: Boolean,
    var shape: Shape,
    var containerColor: Color,
    var focusedContainerColor: Color,
    var unfocusedBorderColor: Color,
    var focusedBorderColor: Color,
    var borderWidth: Dp,
    var focusedBorderWidth: Dp,
    var focusScale: Float,
    var onFocusChanged: ((Boolean) -> Unit)?
) : Modifier.Node(), FocusEventModifierNode, LayoutModifierNode, DrawModifierNode {

    private var isFocused: Boolean = false
    private val currentScaleState = mutableFloatStateOf(1f)
    private var scaleAnim: Animatable<Float, AnimationVector1D>? = null
    private var scaleJob: Job? = null

    override fun onFocusEvent(focusState: FocusState) {
        val now = focusState.isFocused
        if (isFocused == now) return
        isFocused = now
        invalidateDraw()
        syncScaleTarget()
        onFocusChanged?.invoke(now)
    }

    fun syncScaleTarget() {
        val target = if (isFocused && enabled) focusScale else 1f
        if (!isAttached) {
            currentScaleState.floatValue = target
            return
        }
        if (target == currentScaleState.floatValue && scaleJob?.isActive != true) return
        scaleJob?.cancel()
        scaleJob = coroutineScope.launch {
            val anim = scaleAnim ?: Animatable(currentScaleState.floatValue).also { scaleAnim = it }
            anim.animateTo(target, animationSpec = FOCUS_SCALE_SPEC) {
                currentScaleState.floatValue = value
            }
        }
    }

    override fun onDetach() {
        scaleJob?.cancel()
        scaleJob = null
        super.onDetach()
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                val s = currentScaleState.floatValue
                scaleX = s
                scaleY = s
                val nodeShape = shape
                if (nodeShape !== RectangleShape) {
                    this.shape = nodeShape
                    clip = true
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val activeFocus = isFocused && enabled
        val bg = if (activeFocus) focusedContainerColor else containerColor
        val nodeShape = shape
        val cachedOutline = if (nodeShape !== RectangleShape && nodeShape !== CircleShape) {
            nodeShape.createOutline(size, layoutDirection, this)
        } else {
            null
        }

        if (bg != Color.Transparent && bg != Color.Unspecified) {
            when (nodeShape) {
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
                when (nodeShape) {
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
}
