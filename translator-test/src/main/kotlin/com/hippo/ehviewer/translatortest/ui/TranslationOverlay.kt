package com.hippo.ehviewer.translatortest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hippo.ehviewer.translatortest.TranslatedBubble
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun TranslationOverlay(
    bubbles: List<TranslatedBubble>,
    bitmapWidth: Int,
    bitmapHeight: Int,
    globalBubbleScale: Float = 1.25f,
    horizontalExpansion: Float = 2.0f,
    globalFontScale: Float = 1.0f,
    bubbleOpacity: Float = 0.95f,
    dynamicBubbleSize: Boolean = true,
    onBubbleClick: (TranslatedBubble) -> Unit,
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

        bubbles.forEach { bubble ->
            DynamicBubbleView(
                bubble = bubble,
                scaleX = scaleX,
                scaleY = scaleY,
                offsetX = offsetX,
                offsetY = offsetY,
                containerWidthPx = containerWidthPx,
                containerHeightPx = containerHeightPx,
                globalBubbleScale = globalBubbleScale,
                horizontalExpansion = horizontalExpansion,
                globalFontScale = globalFontScale,
                bubbleOpacity = bubbleOpacity,
                dynamicBubbleSize = dynamicBubbleSize,
                onBubbleClick = onBubbleClick,
            )
        }
    }
}

@Composable
private fun DynamicBubbleView(
    bubble: TranslatedBubble,
    scaleX: Float,
    scaleY: Float,
    offsetX: Float,
    offsetY: Float,
    containerWidthPx: Float,
    containerHeightPx: Float,
    globalBubbleScale: Float,
    horizontalExpansion: Float,
    globalFontScale: Float,
    bubbleOpacity: Float,
    dynamicBubbleSize: Boolean,
    onBubbleClick: (TranslatedBubble) -> Unit,
) {
    val density = LocalDensity.current
    val origWidthPx = (bubble.boundingBox.width() * scaleX).coerceAtLeast(10f)
    val origHeightPx = (bubble.boundingBox.height() * scaleY).coerceAtLeast(10f)

    val centerX = offsetX + (bubble.boundingBox.centerX() * scaleX)
    val centerY = offsetY + (bubble.boundingBox.centerY() * scaleY)

    val isVerticalText = origHeightPx > origWidthPx * 1.15f
    val baseWidth = if (isVerticalText) {
        maxOf(
            origWidthPx * horizontalExpansion,
            minOf(origHeightPx * 0.72f, containerWidthPx * 0.48f),
        )
    } else {
        origWidthPx * 1.25f
    }
    val baseHeight = origHeightPx * 1.08f

    val targetWidthPx = (baseWidth * globalBubbleScale * bubble.widthScale)
        .coerceIn(40f, containerWidthPx * 0.85f)
    val targetHeightPx = (baseHeight * globalBubbleScale * bubble.heightScale)
        .coerceIn(24f, containerHeightPx * 0.85f)

    var measuredSize by remember(bubble.translatedText, targetWidthPx, globalFontScale, dynamicBubbleSize) {
        mutableStateOf<IntSize?>(null)
    }

    val finalWidth = if (dynamicBubbleSize && measuredSize != null) {
        measuredSize!!.width.toFloat()
    } else {
        targetWidthPx
    }

    val finalHeight = if (dynamicBubbleSize && measuredSize != null) {
        measuredSize!!.height.toFloat()
    } else {
        targetHeightPx
    }

    val boxLeft = (centerX - (finalWidth / 2f)).coerceIn(
        0f,
        (containerWidthPx - finalWidth).coerceAtLeast(0f),
    )
    val boxTop = (centerY - (finalHeight / 2f)).coerceIn(
        0f,
        (containerHeightPx - finalHeight).coerceAtLeast(0f),
    )

    // Dynamic typography
    val textLen = bubble.translatedText.length.coerceAtLeast(1)
    val area = targetWidthPx * targetHeightPx
    val charArea = area / textLen
    val estimatedSp = (sqrt(charArea) * 0.72f).coerceIn(8.5f, 20f)
    val finalFontSizeSp = bubble.customFontSizeSp ?: (estimatedSp * globalFontScale).coerceIn(7.5f, 26f)

    val targetWidthDp = with(density) { targetWidthPx.toDp() }
    val targetHeightDp = with(density) { targetHeightPx.toDp() }

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
            .background(Color.White.copy(alpha = bubbleOpacity), RoundedCornerShape(8.dp))
            .border(1.dp, Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
            .clickable { onBubbleClick(bubble) }
            .padding(horizontal = 4.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = bubble.translatedText,
            color = Color.Black,
            fontSize = finalFontSizeSp.sp,
            lineHeight = (finalFontSizeSp * 1.18f).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            softWrap = true,
            maxLines = if (dynamicBubbleSize) Int.MAX_VALUE else 12,
        )
    }
}
