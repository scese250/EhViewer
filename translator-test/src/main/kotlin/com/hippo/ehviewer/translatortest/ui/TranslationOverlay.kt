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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hippo.ehviewer.translatortest.TranslatedBubble
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
            val origWidthPx = (bubble.boundingBox.width() * scaleX).coerceAtLeast(10f)
            val origHeightPx = (bubble.boundingBox.height() * scaleY).coerceAtLeast(10f)

            val centerX = offsetX + (bubble.boundingBox.centerX() * scaleX)
            val centerY = offsetY + (bubble.boundingBox.centerY() * scaleY)

            // In manga, speech bubbles are tall and narrow for Japanese vertical text.
            // Horizontal languages (Spanish/English) need wider boxes to avoid breaking words into single letters.
            val isVerticalText = origHeightPx > origWidthPx * 1.15f
            val baseWidth = if (isVerticalText) {
                maxOf(
                    origWidthPx * horizontalExpansion,
                    minOf(origHeightPx * 0.70f, containerWidthPx * 0.42f),
                )
            } else {
                origWidthPx * 1.2f
            }
            val baseHeight = origHeightPx * 1.08f

            val finalWidthPx = baseWidth * globalBubbleScale * bubble.widthScale
            val finalHeightPx = baseHeight * globalBubbleScale * bubble.heightScale

            val boxLeft = (centerX - (finalWidthPx / 2f)).coerceIn(
                0f,
                (containerWidthPx - finalWidthPx).coerceAtLeast(0f),
            )
            val boxTop = (centerY - (finalHeightPx / 2f)).coerceIn(
                0f,
                (containerHeightPx - finalHeightPx).coerceAtLeast(0f),
            )

            val boxLeftDp = with(density) { boxLeft.toDp() }
            val boxTopDp = with(density) { boxTop.toDp() }
            val boxWidthDp = with(density) { finalWidthPx.toDp() }
            val boxHeightDp = with(density) { finalHeightPx.toDp() }

            // Dynamic font sizing
            val textLen = bubble.translatedText.length.coerceAtLeast(1)
            val area = finalWidthPx * finalHeightPx
            val charArea = area / textLen
            val estimatedSp = (sqrt(charArea) * 0.72f).coerceIn(8.5f, 22f)
            val finalFontSizeSp = bubble.customFontSizeSp ?: (estimatedSp * globalFontScale).coerceIn(7f, 26f)

            Box(
                modifier = Modifier
                    .offset(x = boxLeftDp, y = boxTopDp)
                    .width(boxWidthDp)
                    .height(boxHeightDp)
                    .background(Color.White.copy(alpha = bubbleOpacity), RoundedCornerShape(8.dp))
                    .border(1.dp, Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                    .clickable { onBubbleClick(bubble) }
                    .padding(horizontal = 3.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = bubble.translatedText,
                    color = Color.Black,
                    fontSize = finalFontSizeSp.sp,
                    lineHeight = (finalFontSizeSp * 1.16f).sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 12,
                )
            }
        }
    }
}
