package com.hippo.ehviewer.translatortest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hippo.ehviewer.translatortest.TranslatedBubble
import com.hippo.ehviewer.translatortest.TranslatorSettings
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun TranslationOverlay(
    bubbles: List<TranslatedBubble>,
    bitmapWidth: Int,
    bitmapHeight: Int,
    globalBubbleScale: Float = 1.0f,
    horizontalExpansion: Float = 1.3f,
    globalFontScale: Float = 1.0f,
    bubbleOpacity: Float = 0.95f,
    dynamicBubbleSize: Boolean = true,
    bubbleOrientation: String = TranslatorSettings.ORIENTATION_VERTICAL,
    bubbleShape: String = TranslatorSettings.SHAPE_OVAL,
    uppercaseScanlation: Boolean = true,
    preventCollisions: Boolean = true,
    borderThickness: Float = 1.5f,
    isManipulationEnabled: Boolean = false,
    onBubbleClick: (TranslatedBubble) -> Unit,
    onBubbleUpdated: (TranslatedBubble) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (bitmapWidth <= 0 || bitmapHeight <= 0 || bubbles.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }

        if (containerWidthPx <= 0 || containerHeightPx <= 0) return@BoxWithConstraints

        val imageAspect = bitmapWidth.toFloat() / bitmapHeight.toFloat()
        val containerAspect = containerWidthPx / containerHeightPx

        val displayedWidth: Float
        val displayedHeight: Float
        val offsetX: Float
        val offsetY: Float

        if (containerAspect > imageAspect) {
            displayedHeight = containerHeightPx
            displayedWidth = displayedHeight * imageAspect
            offsetX = (containerWidthPx - displayedWidth) / 2f
            offsetY = 0f
        } else {
            displayedWidth = containerWidthPx
            displayedHeight = displayedWidth / imageAspect
            offsetX = 0f
            offsetY = (containerHeightPx - displayedHeight) / 2f
        }

        val scaleX = displayedWidth / bitmapWidth.toFloat()
        val scaleY = displayedHeight / bitmapHeight.toFloat()

        // Pre-compute bubble layout and apply intelligent rolling proximity repulsion
        val bubbleLayouts = remember(
            bubbles,
            containerWidthPx,
            containerHeightPx,
            scaleX,
            scaleY,
            offsetX,
            offsetY,
            globalBubbleScale,
            globalFontScale,
            horizontalExpansion,
            bubbleOrientation,
            uppercaseScanlation,
            preventCollisions,
        ) {
            val initialList = bubbles.map { bubble ->
                val origWidthPx = (bubble.boundingBox.width() * scaleX).coerceAtLeast(12f)
                val origHeightPx = (bubble.boundingBox.height() * scaleY).coerceAtLeast(12f)
                val bubbleCenterX = offsetX + (bubble.boundingBox.centerX() * scaleX) +
                    with(density) { bubble.offsetX.dp.toPx() }
                val bubbleCenterY = offsetY + (bubble.boundingBox.centerY() * scaleY) +
                    with(density) { bubble.offsetY.dp.toPx() }

                val displayText = if (uppercaseScanlation) {
                    bubble.translatedText.uppercase()
                } else {
                    bubble.translatedText
                }
                val words = displayText.split("\\s+".toRegex()).filter { it.isNotBlank() }
                val longestWordLen = words.maxOfOrNull { it.length } ?: 1
                val textLen = displayText.length.coerceAtLeast(1)

                val isVertical = bubble.isVertical ?: when (bubbleOrientation) {
                    TranslatorSettings.ORIENTATION_VERTICAL -> true
                    TranslatorSettings.ORIENTATION_HORIZONTAL -> false
                    else -> origHeightPx > origWidthPx * 1.15f
                }

                val autoFontSizeSp = if (isVertical) {
                    val baseArea = origWidthPx * 1.15f * origHeightPx * 1.15f
                    val charArea = baseArea / textLen
                    (sqrt(charArea / (density.density * density.density)) * 0.74f).coerceIn(9f, 15f)
                } else {
                    val baseArea = maxOf(origWidthPx * 1.4f, 80f * density.density) * origHeightPx * 1.15f
                    val charArea = baseArea / textLen
                    (sqrt(charArea / (density.density * density.density)) * 0.76f).coerceIn(9.5f, 16f)
                }
                val finalFontSizeSp = bubble.customFontSizeSp ?: (autoFontSizeSp * globalFontScale).coerceIn(8.5f, 24f)

                val approxCharWidthPx = finalFontSizeSp * density.density * 0.58f
                val minWordWidthPx = (longestWordLen * approxCharWidthPx) + (16f * density.density)

                val targetWidthPx = if (isVertical) {
                    val vBase = maxOf(origWidthPx * 1.08f, minWordWidthPx, 44f * density.density)
                    (vBase * globalBubbleScale * bubble.widthScale).coerceIn(minWordWidthPx, containerWidthPx * 0.70f)
                } else {
                    val hBase = maxOf(origWidthPx * horizontalExpansion, 75f * density.density, minWordWidthPx)
                    (hBase * globalBubbleScale * bubble.widthScale).coerceIn(minWordWidthPx, containerWidthPx * 0.90f)
                }

                val targetHeightPx = (origHeightPx * 1.15f * globalBubbleScale * bubble.heightScale)
                    .coerceIn(28f, containerHeightPx * 0.85f)

                val left = (bubbleCenterX - targetWidthPx / 2f)
                    .coerceIn(0f, (containerWidthPx - targetWidthPx).coerceAtLeast(0f))
                val top = (bubbleCenterY - targetHeightPx / 2f)
                    .coerceIn(0f, (containerHeightPx - targetHeightPx).coerceAtLeast(0f))

                BubbleLayoutItem(
                    id = bubble.id,
                    left = left,
                    top = top,
                    width = targetWidthPx,
                    height = targetHeightPx,
                    fontSizeSp = finalFontSizeSp,
                    isVertical = isVertical,
                )
            }

            if (preventCollisions && initialList.size > 1) {
                resolveBubbleProximityAndCollisions(initialList, containerWidthPx, containerHeightPx)
            }
            initialList.associateBy { it.id }
        }

        bubbles.forEach { bubble ->
            val layout = bubbleLayouts[bubble.id]
            if (layout != null) {
                DynamicBubbleView(
                    bubble = bubble,
                    layout = layout,
                    containerWidthPx = containerWidthPx,
                    containerHeightPx = containerHeightPx,
                    bubbleOpacity = bubbleOpacity,
                    dynamicBubbleSize = dynamicBubbleSize,
                    bubbleShape = bubbleShape,
                    uppercaseScanlation = uppercaseScanlation,
                    borderThickness = borderThickness,
                    isManipulationEnabled = isManipulationEnabled,
                    onBubbleClick = onBubbleClick,
                    onBubbleUpdated = onBubbleUpdated,
                )
            }
        }
    }
}

@Composable
private fun DynamicBubbleView(
    bubble: TranslatedBubble,
    layout: BubbleLayoutItem,
    containerWidthPx: Float,
    containerHeightPx: Float,
    bubbleOpacity: Float,
    dynamicBubbleSize: Boolean,
    bubbleShape: String,
    uppercaseScanlation: Boolean,
    borderThickness: Float,
    isManipulationEnabled: Boolean,
    onBubbleClick: (TranslatedBubble) -> Unit,
    onBubbleUpdated: (TranslatedBubble) -> Unit,
) {
    val density = LocalDensity.current
    val displayText = if (uppercaseScanlation) {
        bubble.translatedText.uppercase()
    } else {
        bubble.translatedText
    }

    var measuredSize by remember(displayText, layout.width, layout.fontSizeSp, dynamicBubbleSize) {
        mutableStateOf<IntSize?>(null)
    }

    val finalHeight = if (dynamicBubbleSize && measuredSize != null) {
        measuredSize!!.height.toFloat()
    } else {
        layout.height
    }

    val centerY = layout.top + (layout.height / 2f)
    val boxLeft = layout.left.coerceIn(0f, (containerWidthPx - layout.width).coerceAtLeast(0f))
    val boxTop = (centerY - (finalHeight / 2f)).coerceIn(
        0f,
        (containerHeightPx - finalHeight).coerceAtLeast(0f),
    )

    val targetWidthDp = with(density) { layout.width.toDp() }
    val targetHeightDp = with(density) { layout.height.toDp() }

    val bubbleShapeDrawable = when (bubbleShape) {
        TranslatorSettings.SHAPE_OVAL -> RoundedCornerShape(percent = 45)
        TranslatorSettings.SHAPE_ROUNDED -> RoundedCornerShape(12.dp)
        TranslatorSettings.SHAPE_BORDERLESS -> RoundedCornerShape(percent = 45)
        else -> RoundedCornerShape(percent = 45)
    }

    val borderModifier = when {
        isManipulationEnabled -> {
            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, bubbleShapeDrawable)
        }
        bubbleShape == TranslatorSettings.SHAPE_BORDERLESS || borderThickness <= 0f -> {
            Modifier
        }
        else -> {
            Modifier.border(borderThickness.dp, Color.Black.copy(alpha = 0.85f), bubbleShapeDrawable)
        }
    }

    val touchModifier = if (isManipulationEnabled) {
        Modifier.pointerInput(bubble.id) {
            detectTransformGestures { _, pan, zoom, _ ->
                val panXdp = pan.x / density.density
                val panYdp = pan.y / density.density
                val newWidthScale = (bubble.widthScale * zoom).coerceIn(0.4f, 3.5f)
                val newHeightScale = (bubble.heightScale * zoom).coerceIn(0.4f, 3.5f)
                onBubbleUpdated(
                    bubble.copy(
                        offsetX = bubble.offsetX + panXdp,
                        offsetY = bubble.offsetY + panYdp,
                        widthScale = newWidthScale,
                        heightScale = newHeightScale,
                    ),
                )
            }
        }
    } else {
        Modifier.clickable { onBubbleClick(bubble) }
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(boxLeft.roundToInt(), boxTop.roundToInt()) }
            .width(targetWidthDp)
            .then(
                if (dynamicBubbleSize) {
                    Modifier.wrapContentHeight()
                } else {
                    Modifier.height(targetHeightDp)
                },
            )
            .onSizeChanged { size ->
                measuredSize = size
            }
            .background(Color.White.copy(alpha = bubbleOpacity), bubbleShapeDrawable)
            .then(borderModifier)
            .then(touchModifier)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = displayText,
            color = Color.Black,
            fontSize = layout.fontSizeSp.sp,
            lineHeight = (layout.fontSizeSp * 1.18f).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            softWrap = true,
            maxLines = if (dynamicBubbleSize) Int.MAX_VALUE else 12,
        )

        // Manipulation Handles when editing mode is active
        if (isManipulationEnabled) {
            // Top-right edit button (opens full dialog)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(20.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .clickable { onBubbleClick(bubble) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Editar",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }

            // Bottom-right corner resize handle (drag to scale)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 4.dp, y = 4.dp)
                    .size(18.dp)
                    .background(MaterialTheme.colorScheme.tertiary, CircleShape)
                    .pointerInput(bubble.id) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val delta = 1f + (dragAmount.x + dragAmount.y) / (80f * density.density)
                            val scaled = (bubble.widthScale * delta).coerceIn(0.4f, 3.5f)
                            onBubbleUpdated(
                                bubble.copy(
                                    widthScale = scaled,
                                    heightScale = scaled,
                                ),
                            )
                        }
                    },
            )
        }
    }
}

private class BubbleLayoutItem(
    val id: Int,
    var left: Float,
    var top: Float,
    val width: Float,
    val height: Float,
    val fontSizeSp: Float,
    val isVertical: Boolean,
) {
    val right get() = left + width
    val bottom get() = top + height
    val centerX get() = left + width / 2f
    val centerY get() = top + height / 2f
}

/**
 * Intelligent proximity and collision repulsion:
 * Detects bubbles that are touching or too close (< minGap), smoothly separating and rolling them away.
 */
private fun resolveBubbleProximityAndCollisions(
    bubbles: List<BubbleLayoutItem>,
    containerWidth: Float,
    containerHeight: Float,
    iterations: Int = 8,
) {
    val minGap = 10f
    for (iter in 0 until iterations) {
        var anyMoved = false
        for (i in bubbles.indices) {
            for (j in i + 1 until bubbles.size) {
                val b1 = bubbles[i]
                val b2 = bubbles[j]

                val overlapX = minOf(b1.right + minGap - b2.left, b2.right + minGap - b1.left)
                val overlapY = minOf(b1.bottom + minGap - b2.top, b2.bottom + minGap - b1.top)

                if (overlapX > 0f && overlapY > 0f) {
                    anyMoved = true
                    val dx = b2.centerX - b1.centerX
                    val dy = b2.centerY - b1.centerY

                    if (abs(dx) >= abs(dy) * 0.70f) {
                        // Primarily horizontal: push apart horizontally and slide slightly vertically (rolling effect)
                        val pushX = (overlapX / 2f).coerceAtLeast(1.5f)
                        val slideY = pushX * 0.25f

                        if (dx >= 0) {
                            b1.left -= pushX
                            b2.left += pushX
                            if (dy >= 0) {
                                b1.top -= slideY
                                b2.top += slideY
                            } else {
                                b1.top += slideY
                                b2.top -= slideY
                            }
                        } else {
                            b1.left += pushX
                            b2.left -= pushX
                            if (dy >= 0) {
                                b1.top -= slideY
                                b2.top += slideY
                            } else {
                                b1.top += slideY
                                b2.top -= slideY
                            }
                        }
                    } else {
                        // Primarily vertical: push apart vertically and slide horizontally
                        val pushY = (overlapY / 2f).coerceAtLeast(1.5f)
                        val slideX = pushY * 0.25f

                        if (dy >= 0) {
                            b1.top -= pushY
                            b2.top += pushY
                            if (dx >= 0) {
                                b1.left -= slideX
                                b2.left += slideX
                            } else {
                                b1.left += slideX
                                b2.left -= slideX
                            }
                        } else {
                            b1.top += pushY
                            b2.top -= pushY
                            if (dx >= 0) {
                                b1.left -= slideX
                                b2.left += slideX
                            } else {
                                b1.left += slideX
                                b2.left -= slideX
                            }
                        }
                    }

                    b1.left = b1.left.coerceIn(0f, (containerWidth - b1.width).coerceAtLeast(0f))
                    b1.top = b1.top.coerceIn(0f, (containerHeight - b1.height).coerceAtLeast(0f))
                    b2.left = b2.left.coerceIn(0f, (containerWidth - b2.width).coerceAtLeast(0f))
                    b2.top = b2.top.coerceIn(0f, (containerHeight - b2.height).coerceAtLeast(0f))
                }
            }
        }
        if (!anyMoved) break
    }
}
