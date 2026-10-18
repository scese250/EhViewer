package com.hippo.ehviewer.translator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.hippo.ehviewer.translator.TranslatedBubble
import com.hippo.ehviewer.translator.TranslatorSettings
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How the page bitmap is laid out inside the overlay container.
 *
 * [INSIDE] mirrors [androidx.compose.ui.layout.ContentScale.Inside] used by the
 * paged reader; [FILL_WIDTH] mirrors [androidx.compose.ui.layout.ContentScale.FillWidth]
 * used by the webtoon reader.
 */
enum class OverlayFitMode {
    INSIDE,
    FILL_WIDTH,
}

@Composable
fun TranslationOverlay(
    bubbles: List<TranslatedBubble>,
    bitmapWidth: Int,
    bitmapHeight: Int,
    globalBubbleScale: Float = 1.0f,
    horizontalExpansion: Float = 1.20f,
    globalFontScale: Float = 0.90f,
    minFontSizeSp: Float = 6.5f,
    maxFontSizeSp: Float = 16f,
    fitTextInsideBubble: Boolean = true,
    maxHeightExpansion: Float = 1.6f,
    bubbleOpacity: Float = 1.0f,
    dynamicBubbleSize: Boolean = true,
    bubbleOrientation: String = TranslatorSettings.ORIENTATION_VERTICAL,
    bubbleShape: String = TranslatorSettings.SHAPE_OVAL,
    bubblePadding: String = TranslatorSettings.PADDING_COMPACT,
    uppercaseScanlation: Boolean = true,
    preventCollisions: Boolean = true,
    collisionGap: Float = 12f,
    collisionIterations: Int = 40,
    maxCollisionShift: Float = 0.30f,
    borderThickness: Float = 1.2f,
    fitMode: OverlayFitMode = OverlayFitMode.INSIDE,
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

        var displayedWidth: Float
        var displayedHeight: Float
        var offsetX: Float
        var offsetY: Float

        if (fitMode == OverlayFitMode.FILL_WIDTH) {
            displayedWidth = containerWidthPx
            displayedHeight = displayedWidth / imageAspect
            offsetX = 0f
            offsetY = 0f
        } else if (containerAspect > imageAspect) {
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

        // ContentScale.Inside never upscales a small page beyond 1:1.
        if (fitMode == OverlayFitMode.INSIDE &&
            (displayedWidth > bitmapWidth || displayedHeight > bitmapHeight)
        ) {
            displayedWidth = bitmapWidth.toFloat()
            displayedHeight = bitmapHeight.toFloat()
            offsetX = (containerWidthPx - displayedWidth) / 2f
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
        // collisionGap is configured in dp; layouts below are in pixels.
        val collisionGapPx = with(density) { collisionGap.dp.toPx() }
        // Defensive: corrupted prefs must not make coerceIn throw.
        val effectiveMinFontSp = minFontSizeSp.coerceAtMost(maxFontSizeSp)
        val effectiveMaxFontSp = maxFontSizeSp.coerceAtLeast(effectiveMinFontSp)

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
            minFontSizeSp,
            maxFontSizeSp,
            fitTextInsideBubble,
            maxHeightExpansion,
            horizontalExpansion,
            bubbleOrientation,
            bubbleShape,
            bubblePadding,
            uppercaseScanlation,
            preventCollisions,
            collisionGap,
            collisionIterations,
            maxCollisionShift,
            dynamicBubbleSize,
            isManipulationEnabled,
            fitMode,
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

                // Base font size from the original balloon area, then clamped by the user.
                val autoFontSizeSp = if (isVertical) {
                    val baseArea = origWidthPx * origHeightPx
                    val charArea = baseArea / textLen
                    (sqrt(charArea / (density.density * density.density)) * 0.70f).coerceIn(6.0f, 14.0f)
                } else {
                    val baseArea = maxOf(origWidthPx * 1.2f, 65f * density.density) * origHeightPx
                    val charArea = baseArea / textLen
                    (sqrt(charArea / (density.density * density.density)) * 0.72f).coerceIn(6.5f, 15.0f)
                }
                var finalFontSizeSp = bubble.customFontSizeSp
                    ?: (autoFontSizeSp * globalFontScale).coerceIn(effectiveMinFontSp, effectiveMaxFontSp)

                fun measureWordWidth(fontSp: Float): Float = words.maxOfOrNull { word ->
                    textMeasurer.measure(
                        text = AnnotatedString(word),
                        style = TextStyle(
                            fontSize = fontSp.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    ).size.width.toFloat()
                } ?: 0f

                fun measureTextHeight(fontSp: Float, widthPx: Float): Float = textMeasurer.measure(
                    text = AnnotatedString(displayText),
                    style = TextStyle(
                        fontSize = fontSp.sp,
                        lineHeight = (fontSp * 1.16f).sp,
                        fontWeight = FontWeight.Bold,
                        lineBreak = LineBreak.Heading,
                        hyphens = Hyphens.None,
                    ),
                    constraints = Constraints(maxWidth = widthPx.roundToInt().coerceAtLeast(1)),
                ).size.height.toFloat()

                // Never let one long word force the balloon wider than the page.
                var maxWordWidthPx = measureWordWidth(finalFontSizeSp)
                while (finalFontSizeSp > effectiveMinFontSp && maxWordWidthPx + hPaddingPx + ovalCurveMarginPx > containerWidthPx * 0.85f) {
                    finalFontSizeSp = (finalFontSizeSp - 0.5f).coerceAtLeast(effectiveMinFontSp)
                    maxWordWidthPx = measureWordWidth(finalFontSizeSp)
                }

                val minRequiredWidthPx = maxWordWidthPx + hPaddingPx + ovalCurveMarginPx

                val desiredWidthPx = if (isVertical) {
                    maxOf(origWidthPx * 1.10f, with(density) { 40.dp.toPx() })
                } else {
                    maxOf(origWidthPx * horizontalExpansion, with(density) { 60.dp.toPx() })
                }
                val maxWidthPx = containerWidthPx * 0.92f
                val baseWidthPx = maxOf(desiredWidthPx * globalBubbleScale, minRequiredWidthPx)
                    .coerceAtMost(maxWidthPx)
                    .coerceAtLeast(minRequiredWidthPx.coerceAtMost(maxWidthPx))

                // Height: keep covering the original balloon. When "fit inside"
                // is enabled, shrink the font until the translation fits instead
                // of growing the box into the neighbouring balloons.
                val coverHeightPx = origHeightPx * (if (fitTextInsideBubble) 1.0f else 1.05f) * globalBubbleScale
                val heightLimitPx = maxOf(24f, containerHeightPx * 0.92f)
                val maxHeightPx = (coverHeightPx * maxHeightExpansion).coerceIn(24f, heightLimitPx)

                // The text is laid out inside the horizontal padding, so the
                // measured width must exclude it or the real rendered height is
                // underestimated and the last lines get clipped.
                val contentWidthPx = (baseWidthPx - hPaddingPx).coerceAtLeast(1f)
                var measuredTextHeightPx = measureTextHeight(finalFontSizeSp, contentWidthPx) + vPaddingPx
                if (fitTextInsideBubble && bubble.customFontSizeSp == null) {
                    var guard = 0
                    while (guard < 80 && finalFontSizeSp > effectiveMinFontSp && measuredTextHeightPx > coverHeightPx) {
                        finalFontSizeSp = (finalFontSizeSp - 0.25f).coerceAtLeast(effectiveMinFontSp)
                        measuredTextHeightPx = measureTextHeight(finalFontSizeSp, contentWidthPx) + vPaddingPx
                        guard++
                    }
                }

                val actualHeightPx = if (dynamicBubbleSize) {
                    maxOf(coverHeightPx, measuredTextHeightPx).coerceIn(24f, maxHeightPx)
                } else {
                    coverHeightPx.coerceIn(24f, maxHeightPx)
                }

                val isManual = bubble.offsetX != 0f || bubble.offsetY != 0f
                val initialLeft = (bubbleCenterX - baseWidthPx / 2f)
                    .coerceIn(0f, (containerWidthPx - baseWidthPx).coerceAtLeast(0f))
                val initialTop = (bubbleCenterY - actualHeightPx / 2f)
                    .coerceIn(0f, (containerHeightPx - actualHeightPx).coerceAtLeast(0f))

                BubbleLayoutItem(
                    id = bubble.id,
                    left = initialLeft,
                    top = initialTop,
                    originLeft = initialLeft,
                    originTop = initialTop,
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

            // Nudge auto-positioned balloons even in touch mode; balloons the
            // user has already moved are anchored and stay put.
            if (preventCollisions && initialList.size > 1) {
                resolveBubbleCollisions(
                    bubbles = initialList,
                    containerWidth = containerWidthPx,
                    containerHeight = containerHeightPx,
                    gapPx = collisionGapPx,
                    iterations = collisionIterations,
                    maxShiftRatio = maxCollisionShift,
                )
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

    // Use the resolved position (after anti-collision) instead of the raw OCR
    // centre, otherwise the collision solver has no visible effect.
    val currentCenterX = layout.centerX + with(density) { liveOffsetX.dp.toPx() }
    val currentCenterY = layout.centerY + with(density) { liveOffsetY.dp.toPx() }

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
        // Never intercept page taps unless the user explicitly enabled the
        // touch manipulation mode from the translation settings.
        Modifier
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
                    // At least the measured height (so the original balloon is
                    // covered), but allowed to grow if the text needs more room.
                    Modifier.heightIn(min = targetHeightDp)
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
    val originLeft: Float,
    val originTop: Float,
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
 * Separates balloons that overlap after the translation has been laid out.
 *
 * The old version only moved its internal layout copies, so nothing changed on
 * screen. The renderer now follows [BubbleLayoutItem.left]/[BubbleLayoutItem.top].
 * This solver separates each colliding pair along the axis that needs the least
 * movement, falls back to the other axis when a container edge blocks it, keeps
 * manually edited balloons fixed, and limits how far a balloon may drift from
 * its detected position.
 */
private fun resolveBubbleCollisions(
    bubbles: List<BubbleLayoutItem>,
    containerWidth: Float,
    containerHeight: Float,
    gapPx: Float,
    iterations: Int = 40,
    maxShiftRatio: Float = 0.30f,
) {
    if (bubbles.size < 2 || iterations <= 0) return

    val shiftRatio = maxShiftRatio.coerceIn(0.05f, 0.60f)
    val maxShiftX = containerWidth * shiftRatio
    val maxShiftY = containerHeight * shiftRatio

    repeat(iterations) {
        var moved = false

        for (i in bubbles.indices) {
            for (j in i + 1 until bubbles.size) {
                val a = bubbles[i]
                val b = bubbles[j]

                // Penetration including the requested breathing gap.
                val overlapX = minOf(a.right, b.right) - maxOf(a.left, b.left) + gapPx
                val overlapY = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) + gapPx
                if (overlapX <= 0f || overlapY <= 0f) continue

                val canMoveA = !a.isManuallyPositioned
                val canMoveB = !b.isManuallyPositioned
                if (!canMoveA && !canMoveB) continue

                val shareA = when {
                    canMoveA && canMoveB -> 0.5f
                    canMoveA -> 1f
                    else -> 0f
                }
                val shareB = 1f - shareA

                val moveHorizontally = overlapX <= overlapY

                if (moveHorizontally) {
                    val direction = if (a.centerX <= b.centerX) -1f else 1f
                    a.left += direction * overlapX * shareA
                    b.left -= direction * overlapX * shareB
                } else {
                    val direction = if (a.centerY <= b.centerY) -1f else 1f
                    a.top += direction * overlapY * shareA
                    b.top -= direction * overlapY * shareB
                }

                clampBubble(a, containerWidth, containerHeight, maxShiftX, maxShiftY)
                clampBubble(b, containerWidth, containerHeight, maxShiftX, maxShiftY)

                // If a container edge or the drift budget blocked the first
                // correction, finish the separation on the other axis.
                val remainingX = minOf(a.right, b.right) - maxOf(a.left, b.left) + gapPx
                val remainingY = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) + gapPx
                if (remainingX > 0f && remainingY > 0f) {
                    if (moveHorizontally) {
                        val direction = if (a.centerY <= b.centerY) -1f else 1f
                        a.top += direction * remainingY * shareA
                        b.top -= direction * remainingY * shareB
                    } else {
                        val direction = if (a.centerX <= b.centerX) -1f else 1f
                        a.left += direction * remainingX * shareA
                        b.left -= direction * remainingX * shareB
                    }
                    clampBubble(a, containerWidth, containerHeight, maxShiftX, maxShiftY)
                    clampBubble(b, containerWidth, containerHeight, maxShiftX, maxShiftY)
                }

                moved = true
            }
        }

        if (!moved) return
    }
}

private fun clampBubble(
    bubble: BubbleLayoutItem,
    containerWidth: Float,
    containerHeight: Float,
    maxShiftX: Float,
    maxShiftY: Float,
) {
    val maxLeft = (containerWidth - bubble.width).coerceAtLeast(0f)
    val minLeft = (bubble.originLeft - maxShiftX).coerceIn(0f, maxLeft)
    val limitLeft = (bubble.originLeft + maxShiftX).coerceIn(minLeft, maxLeft)
    bubble.left = bubble.left.coerceIn(minLeft, limitLeft)

    val maxTop = (containerHeight - bubble.height).coerceAtLeast(0f)
    val minTop = (bubble.originTop - maxShiftY).coerceIn(0f, maxTop)
    val limitTop = (bubble.originTop + maxShiftY).coerceIn(minTop, maxTop)
    bubble.top = bubble.top.coerceIn(minTop, limitTop)
}
