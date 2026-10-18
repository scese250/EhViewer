package com.hippo.ehviewer.translatortest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
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
    horizontalExpansion: Float = 1.20f,
    globalFontScale: Float = 0.90f,
    bubbleOpacity: Float = 1.0f,
    dynamicBubbleSize: Boolean = true,
    bubbleOrientation: String = TranslatorSettings.ORIENTATION_VERTICAL,
    bubbleShape: String = TranslatorSettings.SHAPE_OVAL,
    bubblePadding: String = TranslatorSettings.PADDING_COMPACT,
    uppercaseScanlation: Boolean = true,
    preventCollisions: Boolean = true,
    collisionGap: Float = 12f,
    borderThickness: Float = 1.2f,
    isManipulationEnabled: Boolean = false,
    onBubbleClick: (TranslatedBubble) -> Unit,
    onBubbleUpdated: (TranslatedBubble) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (bitmapWidth <= 0 || bitmapHeight <= 0 || bubbles.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()

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

        val (hPadDp, vPadDp) = when (bubblePadding) {
            TranslatorSettings.PADDING_COMPACT -> Pair(6.dp, 3.dp)
            TranslatorSettings.PADDING_SPACIOUS -> Pair(14.dp, 8.dp)
            else -> Pair(9.dp, 5.dp)
        }
        val hPaddingPx = with(density) { (hPadDp * 2).toPx() }
        val vPaddingPx = with(density) { (vPadDp * 2).toPx() }
        val ovalCurveMarginPx = if (bubbleShape == TranslatorSettings.SHAPE_OVAL) with(density) { 8.dp.toPx() } else 0f

        // Pre-compute bubble layout with true rendered text heights for reliable collision detection
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
            bubbleShape,
            bubblePadding,
            uppercaseScanlation,
            preventCollisions,
            collisionGap,
            dynamicBubbleSize,
            isManipulationEnabled,
        ) {
            val initialList = bubbles.map { bubble ->
                val origWidthPx = (bubble.boundingBox.width() * scaleX).coerceAtLeast(14f)
                val origHeightPx = (bubble.boundingBox.height() * scaleY).coerceAtLeast(14f)
                val bubbleCenterX = offsetX + (bubble.boundingBox.centerX() * scaleX)
                val bubbleCenterY = offsetY + (bubble.boundingBox.centerY() * scaleY)

                val rawText = if (uppercaseScanlation) {
                    bubble.translatedText.uppercase()
                } else {
                    bubble.translatedText
                }
                val displayText = rawText
                    .replace(Regex("[\\r\\n]+"), " ")
                    .replace(Regex("([¿¡])\\s+"), "$1")
                    .replace(Regex("\\s+([!?,.:;])"), "$1")
                    .replace(Regex("\\s+"), " ")
                    .trim()

                val words = displayText.split(Regex("\\s+")).filter { it.isNotBlank() }
                val textLen = displayText.length.coerceAtLeast(1)

                val isVertical = bubble.isVertical ?: when (bubbleOrientation) {
                    TranslatorSettings.ORIENTATION_VERTICAL -> true
                    TranslatorSettings.ORIENTATION_HORIZONTAL -> false
                    else -> origHeightPx > origWidthPx * 1.15f
                }

                // Authentic manga typography font sizing (compact 7.5sp - 11.5sp)
                val autoFontSizeSp = if (isVertical) {
                    val baseArea = origWidthPx * origHeightPx
                    val charArea = baseArea / textLen
                    (sqrt(charArea / (density.density * density.density)) * 0.70f).coerceIn(7.5f, 11.5f)
                } else {
                    val baseArea = maxOf(origWidthPx * 1.2f, 65f * density.density) * origHeightPx
                    val charArea = baseArea / textLen
                    (sqrt(charArea / (density.density * density.density)) * 0.72f).coerceIn(8.0f, 12.0f)
                }
                var finalFontSizeSp = bubble.customFontSizeSp ?: (autoFontSizeSp * globalFontScale).coerceIn(6.5f, 18f)

                // Measure the exact longest word in physical pixels
                var maxWordWidthPx = words.maxOfOrNull { word ->
                    textMeasurer.measure(
                        text = AnnotatedString(word),
                        style = TextStyle(
                            fontSize = finalFontSizeSp.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    ).size.width
                } ?: 0

                // If word is exceptionally long, scale down font slightly so bubble doesn't blow up
                while (finalFontSizeSp > 7.5f && (maxWordWidthPx + hPaddingPx + ovalCurveMarginPx) > containerWidthPx * 0.85f) {
                    finalFontSizeSp -= 0.5f
                    maxWordWidthPx = words.maxOfOrNull { word ->
                        textMeasurer.measure(
                            text = AnnotatedString(word),
                            style = TextStyle(
                                fontSize = finalFontSizeSp.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        ).size.width
                    } ?: 0
                }

                val minRequiredWidthPx = maxWordWidthPx + hPaddingPx + ovalCurveMarginPx

                val baseWidthPx = if (isVertical) {
                    val vBase = maxOf(origWidthPx * 1.05f, minRequiredWidthPx, with(density) { 40.dp.toPx() })
                    (vBase * globalBubbleScale).coerceAtLeast(minRequiredWidthPx)
                } else {
                    val hBase = maxOf(origWidthPx * horizontalExpansion, with(density) { 60.dp.toPx() }, minRequiredWidthPx)
                    (hBase * globalBubbleScale).coerceAtLeast(minRequiredWidthPx)
                }

                // Measure exact multi-line rendered text height before collision detection
                val measuredTextLayout = textMeasurer.measure(
                    text = AnnotatedString(displayText),
                    style = TextStyle(
                        fontSize = finalFontSizeSp.sp,
                        lineHeight = (finalFontSizeSp * 1.16f).sp,
                        fontWeight = FontWeight.Bold,
                        lineBreak = LineBreak.Heading,
                        hyphens = Hyphens.None,
                    ),
                    constraints = Constraints(maxWidth = baseWidthPx.roundToInt()),
                )
                val measuredTextHeightPx = measuredTextLayout.size.height.toFloat() + vPaddingPx
                val actualHeightPx = if (dynamicBubbleSize) {
                    maxOf(origHeightPx * globalBubbleScale, measuredTextHeightPx).coerceIn(24f, containerHeightPx * 0.90f)
                } else {
                    (origHeightPx * 1.05f * globalBubbleScale).coerceIn(24f, containerHeightPx * 0.90f)
                }

                val isManual = bubble.offsetX != 0f || bubble.offsetY != 0f

                BubbleLayoutItem(
                    id = bubble.id,
                    left = (bubbleCenterX - baseWidthPx / 2f).coerceIn(0f, (containerWidthPx - baseWidthPx).coerceAtLeast(0f)),
                    top = (bubbleCenterY - actualHeightPx / 2f).coerceIn(0f, (containerHeightPx - actualHeightPx).coerceAtLeast(0f)),
                    baseWidthPx = baseWidthPx,
                    baseHeightPx = actualHeightPx,
                    minRequiredWidthPx = minRequiredWidthPx,
                    baseCenterX = bubbleCenterX,
                    baseCenterY = bubbleCenterY,
                    fontSizeSp = finalFontSizeSp,
                    isVertical = isVertical,
                    isManuallyPositioned = isManual,
                )
            }

            // Only run proximity anti-collision in auto mode when user is not actively editing
            if (preventCollisions && !isManipulationEnabled && initialList.size > 1) {
                resolveBubbleProximityAndCollisions(initialList, containerWidthPx, containerHeightPx, minGap = collisionGap)
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
                    bubblePadding = bubblePadding,
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
    bubblePadding: String,
    uppercaseScanlation: Boolean,
    borderThickness: Float,
    isManipulationEnabled: Boolean,
    onBubbleClick: (TranslatedBubble) -> Unit,
    onBubbleUpdated: (TranslatedBubble) -> Unit,
) {
    val density = LocalDensity.current
    val currentBubble by rememberUpdatedState(bubble)
    val currentOnBubbleUpdated by rememberUpdatedState(onBubbleUpdated)
    val currentOnBubbleClick by rememberUpdatedState(onBubbleClick)

    var liveOffsetX by remember(bubble.id) { mutableFloatStateOf(bubble.offsetX) }
    var liveOffsetY by remember(bubble.id) { mutableFloatStateOf(bubble.offsetY) }
    var liveWidthScale by remember(bubble.id) { mutableFloatStateOf(bubble.widthScale) }
    var liveHeightScale by remember(bubble.id) { mutableFloatStateOf(bubble.heightScale) }

    LaunchedEffect(bubble.offsetX, bubble.offsetY, bubble.widthScale, bubble.heightScale) {
        liveOffsetX = bubble.offsetX
        liveOffsetY = bubble.offsetY
        liveWidthScale = bubble.widthScale
        liveHeightScale = bubble.heightScale
    }

    val rawText = if (uppercaseScanlation) {
        bubble.translatedText.uppercase()
    } else {
        bubble.translatedText
    }
    val displayText = rawText
        .replace(Regex("[\\r\\n]+"), " ")
        .replace(Regex("([¿¡])\\s+"), "$1")
        .replace(Regex("\\s+([!?,.:;])"), "$1")
        .replace(Regex("\\s+"), " ")
        .trim()

    var measuredHeightPx by remember(displayText, liveWidthScale, layout.fontSizeSp, dynamicBubbleSize) {
        mutableFloatStateOf(layout.baseHeightPx)
    }

    val liveWidthPx = (layout.baseWidthPx * liveWidthScale).coerceAtLeast(layout.minRequiredWidthPx)
    val liveHeightPx = if (dynamicBubbleSize) {
        measuredHeightPx * liveHeightScale
    } else {
        layout.baseHeightPx * liveHeightScale
    }.coerceAtLeast(20f)

    val currentCenterX = layout.baseCenterX + with(density) { liveOffsetX.dp.toPx() }
    val currentCenterY = layout.baseCenterY + with(density) { liveOffsetY.dp.toPx() }

    val boxLeft = (currentCenterX - liveWidthPx / 2f).coerceIn(0f, (containerWidthPx - liveWidthPx).coerceAtLeast(0f))
    val boxTop = (currentCenterY - liveHeightPx / 2f).coerceIn(0f, (containerHeightPx - liveHeightPx).coerceAtLeast(0f))

    val targetWidthDp = with(density) { liveWidthPx.toDp() }
    val targetHeightDp = with(density) { liveHeightPx.toDp() }

    val bubbleShapeDrawable = when (bubbleShape) {
        TranslatorSettings.SHAPE_OVAL -> RoundedCornerShape(percent = 45)
        TranslatorSettings.SHAPE_ROUNDED -> RoundedCornerShape(8.dp)
        TranslatorSettings.SHAPE_BORDERLESS -> RoundedCornerShape(percent = 45)
        else -> RoundedCornerShape(percent = 45)
    }

    val borderModifier = when {
        isManipulationEnabled -> {
            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, bubbleShapeDrawable)
        }
        bubbleShape == TranslatorSettings.SHAPE_BORDERLESS || borderThickness <= 0.05f -> {
            Modifier
        }
        else -> {
            Modifier.border(borderThickness.dp, Color.Black.copy(alpha = 0.85f), bubbleShapeDrawable)
        }
    }

    val touchModifier = if (isManipulationEnabled) {
        Modifier.pointerInput(bubble.id, isManipulationEnabled) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val initialPos = down.position
                val bWidth = size.width.toFloat()
                val bHeight = size.height.toFloat()

                // Edge regions: outer 28% of the bubble bounds
                val isLeftEdge = initialPos.x < bWidth * 0.28f
                val isRightEdge = initialPos.x > bWidth * 0.72f
                val isTopEdge = initialPos.y < bHeight * 0.28f
                val isBottomEdge = initialPos.y > bHeight * 0.72f
                val isEdgeTouch = isLeftEdge || isRightEdge || isTopEdge || isBottomEdge

                var prevPinchDistance = 0f
                var totalDragDistance = 0f

                while (true) {
                    val event = awaitPointerEvent()
                    val activePointers = event.changes.filter { it.pressed }
                    if (activePointers.isEmpty()) break

                    if (activePointers.size >= 2) {
                        // Two-finger pinch: scale both dimensions uniformly
                        val p1 = activePointers[0].position
                        val p2 = activePointers[1].position
                        val currentPinchDistance = (p1 - p2).getDistance()

                        if (prevPinchDistance > 0f && currentPinchDistance > 0f) {
                            val zoomFactor = currentPinchDistance / prevPinchDistance
                            liveWidthScale = (liveWidthScale * zoomFactor).coerceIn(0.4f, 4.0f)
                            liveHeightScale = (liveHeightScale * zoomFactor).coerceIn(0.4f, 4.0f)
                        }
                        prevPinchDistance = currentPinchDistance
                        totalDragDistance += 10f
                        activePointers.forEach { it.consume() }
                    } else if (activePointers.size == 1) {
                        val change = activePointers[0]
                        val dragDelta = change.position - change.previousPosition
                        totalDragDistance += dragDelta.getDistance()

                        if (isEdgeTouch) {
                            if (isRightEdge) {
                                val factor = dragDelta.x / (bWidth * 0.45f).coerceAtLeast(15f)
                                liveWidthScale = (liveWidthScale + factor).coerceIn(0.4f, 4.0f)
                            } else if (isLeftEdge) {
                                val factor = -dragDelta.x / (bWidth * 0.45f).coerceAtLeast(15f)
                                liveWidthScale = (liveWidthScale + factor).coerceIn(0.4f, 4.0f)
                            }

                            if (isBottomEdge) {
                                val factor = dragDelta.y / (bHeight * 0.45f).coerceAtLeast(15f)
                                liveHeightScale = (liveHeightScale + factor).coerceIn(0.4f, 4.0f)
                            } else if (isTopEdge) {
                                val factor = -dragDelta.y / (bHeight * 0.45f).coerceAtLeast(15f)
                                liveHeightScale = (liveHeightScale + factor).coerceIn(0.4f, 4.0f)
                            }
                            change.consume()
                        } else {
                            // Center drag: move (pan)
                            liveOffsetX += dragDelta.x / density.density
                            liveOffsetY += dragDelta.y / density.density
                            change.consume()
                        }
                    }
                }

                if (totalDragDistance < 8f * density.density) {
                    currentOnBubbleClick(
                        currentBubble.copy(
                            offsetX = liveOffsetX,
                            offsetY = liveOffsetY,
                            widthScale = liveWidthScale,
                            heightScale = liveHeightScale,
                        ),
                    )
                } else {
                    currentOnBubbleUpdated(
                        currentBubble.copy(
                            offsetX = liveOffsetX,
                            offsetY = liveOffsetY,
                            widthScale = liveWidthScale,
                            heightScale = liveHeightScale,
                        ),
                    )
                }
            }
        }
    } else {
        Modifier.clickable { onBubbleClick(bubble) }
    }

    val (hPadDp, vPadDp) = when (bubblePadding) {
        TranslatorSettings.PADDING_COMPACT -> Pair(6.dp, 3.dp)
        TranslatorSettings.PADDING_SPACIOUS -> Pair(14.dp, 8.dp)
        else -> Pair(9.dp, 5.dp)
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
                if (size.height > 0) {
                    measuredHeightPx = size.height.toFloat()
                }
            }
            .background(Color.White.copy(alpha = bubbleOpacity), bubbleShapeDrawable)
            .then(borderModifier)
            .then(touchModifier)
            .padding(horizontal = hPadDp, vertical = vPadDp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = displayText,
            color = Color.Black,
            style = TextStyle(
                fontSize = layout.fontSizeSp.sp,
                lineHeight = (layout.fontSizeSp * 1.16f).sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineBreak = LineBreak.Heading,
                hyphens = Hyphens.None,
            ),
            softWrap = true,
            maxLines = if (dynamicBubbleSize) Int.MAX_VALUE else 14,
        )
    }
}

private class BubbleLayoutItem(
    val id: Int,
    var left: Float,
    var top: Float,
    val baseWidthPx: Float,
    val baseHeightPx: Float,
    val minRequiredWidthPx: Float,
    val baseCenterX: Float,
    val baseCenterY: Float,
    val fontSizeSp: Float,
    val isVertical: Boolean,
    val isManuallyPositioned: Boolean,
) {
    val width get() = baseWidthPx
    val height get() = baseHeightPx
    val right get() = left + width
    val bottom get() = top + height
    val centerX get() = left + width / 2f
    val centerY get() = top + height / 2f
}

/**
 * Intelligent proximity and collision repulsion:
 * Smoothly separates touching bubbles using true rendered heights while respecting manual positioning.
 */
private fun resolveBubbleProximityAndCollisions(
    bubbles: List<BubbleLayoutItem>,
    containerWidth: Float,
    containerHeight: Float,
    minGap: Float = 12f,
    iterations: Int = 10,
) {
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

                    val canMove1 = !b1.isManuallyPositioned
                    val canMove2 = !b2.isManuallyPositioned
                    if (!canMove1 && !canMove2) continue

                    val moveFactor1 = when {
                        canMove1 && canMove2 -> 0.5f
                        canMove1 -> 1.0f
                        else -> 0.0f
                    }
                    val moveFactor2 = when {
                        canMove1 && canMove2 -> 0.5f
                        canMove2 -> 1.0f
                        else -> 0.0f
                    }

                    if (abs(dx) >= abs(dy) * 0.70f) {
                        val pushX = overlapX.coerceAtLeast(2.5f)
                        val slideY = pushX * 0.30f

                        if (dx >= 0) {
                            b1.left -= pushX * moveFactor1
                            b2.left += pushX * moveFactor2
                            if (dy >= 0) {
                                b1.top -= slideY * moveFactor1
                                b2.top += slideY * moveFactor2
                            } else {
                                b1.top += slideY * moveFactor1
                                b2.top -= slideY * moveFactor2
                            }
                        } else {
                            b1.left += pushX * moveFactor1
                            b2.left -= pushX * moveFactor2
                            if (dy >= 0) {
                                b1.top -= slideY * moveFactor1
                                b2.top += slideY * moveFactor2
                            } else {
                                b1.top += slideY * moveFactor1
                                b2.top -= slideY * moveFactor2
                            }
                        }
                    } else {
                        val pushY = overlapY.coerceAtLeast(2.5f)
                        val slideX = pushY * 0.30f

                        if (dy >= 0) {
                            b1.top -= pushY * moveFactor1
                            b2.top += pushY * moveFactor2
                            if (dx >= 0) {
                                b1.left -= slideX * moveFactor1
                                b2.left += slideX * moveFactor2
                            } else {
                                b1.left += slideX * moveFactor1
                                b2.left -= slideX * moveFactor2
                            }
                        } else {
                            b1.top += pushY * moveFactor1
                            b2.top -= pushY * moveFactor2
                            if (dx >= 0) {
                                b1.left -= slideX * moveFactor1
                                b2.left += slideX * moveFactor2
                            } else {
                                b1.left += slideX * moveFactor1
                                b2.left -= slideX * moveFactor2
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
