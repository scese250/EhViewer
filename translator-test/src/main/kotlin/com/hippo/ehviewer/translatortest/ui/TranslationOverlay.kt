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
    val origWidthPx = (bubble.boundingBox.width() * scaleX).coerceAtLeast(12f)
    val origHeightPx = (bubble.boundingBox.height() * scaleY).coerceAtLeast(12f)

    val centerX = offsetX + (bubble.boundingBox.centerX() * scaleX)
    val centerY = offsetY + (bubble.boundingBox.centerY() * scaleY)

    val words = remember(bubble.translatedText) {
        bubble.translatedText.split("\\s+".toRegex()).filter { it.isNotBlank() }
    }
    val longestWordLen = remember(words) {
        words.maxOfOrNull { it.length } ?: 1
    }
    val textLen = bubble.translatedText.length.coerceAtLeast(1)

    // Base horizontal width according to text length to provide horizontal breathing room
    val minContentWidthPx = when {
        textLen <= 6 -> 55f
        textLen <= 18 -> 85f
        textLen <= 40 -> 115f
        textLen <= 80 -> 140f
        else -> 165f
    } * density.density

    // Estimate suitable font size based on text length and area
    val baseArea = maxOf(origWidthPx * 1.5f, minContentWidthPx) * origHeightPx * 1.15f
    val charArea = baseArea / textLen
    val autoFontSizeSp = (sqrt(charArea / (density.density * density.density)) * 0.78f).coerceIn(9.5f, 16f)
    val finalFontSizeSp = bubble.customFontSizeSp ?: (autoFontSizeSp * globalFontScale).coerceIn(8.5f, 24f)

    // Ensure the box width is NEVER narrower than the longest word + horizontal padding.
    // In Latin typography, approx char width is ~0.60 * fontSizeSp.
    // This strictly prevents words from breaking mid-word with orphan letters on the next line!
    val approxCharWidthPx = finalFontSizeSp * density.density * 0.60f
    val minWordWidthPx = (longestWordLen * approxCharWidthPx) + (20f * density.density)

    val calculatedWidthPx = maxOf(
        origWidthPx * horizontalExpansion,
        minContentWidthPx,
        minWordWidthPx,
    )

    val targetWidthPx = (calculatedWidthPx * globalBubbleScale * bubble.widthScale)
        .coerceIn(minWordWidthPx, containerWidthPx * 0.92f)
    val targetHeightPx = (origHeightPx * 1.12f * globalBubbleScale * bubble.heightScale)
        .coerceIn(28f, containerHeightPx * 0.90f)

    var measuredSize by remember(bubble.translatedText, targetWidthPx, globalFontScale, dynamicBubbleSize) {
        mutableStateOf<IntSize?>(null)
    }

    val finalHeight = if (dynamicBubbleSize && measuredSize != null) {
        measuredSize!!.height.toFloat()
    } else {
        targetHeightPx
    }

    val boxLeft = (centerX - (targetWidthPx / 2f)).coerceIn(
        0f,
        (containerWidthPx - targetWidthPx).coerceAtLeast(0f),
    )
    val boxTop = (centerY - (finalHeight / 2f)).coerceIn(
        0f,
        (containerHeightPx - finalHeight).coerceAtLeast(0f),
    )

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
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = bubble.translatedText,
            color = Color.Black,
            fontSize = finalFontSizeSp.sp,
            lineHeight = (finalFontSizeSp * 1.20f).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            softWrap = true,
            maxLines = if (dynamicBubbleSize) Int.MAX_VALUE else 12,
        )
    }
}
