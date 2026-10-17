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
            val boxLeft = offsetX + (bubble.boundingBox.left * scaleX)
            val boxTop = offsetY + (bubble.boundingBox.top * scaleY)
            val boxWidth = (bubble.boundingBox.width() * scaleX).coerceAtLeast(10f)
            val boxHeight = (bubble.boundingBox.height() * scaleY).coerceAtLeast(10f)

            val boxLeftDp = with(density) { boxLeft.toDp() }
            val boxTopDp = with(density) { boxTop.toDp() }
            val boxWidthDp = with(density) { boxWidth.toDp() }
            val boxHeightDp = with(density) { boxHeight.toDp() }

            // Dynamic font sizing based on box area and translated text length
            val textLen = bubble.translatedText.length.coerceAtLeast(1)
            val area = boxWidth * boxHeight
            val charArea = area / textLen
            val calculatedSp = (sqrt(charArea) * 0.70f).coerceIn(8f, 16f)

            Box(
                modifier = Modifier
                    .offset(x = boxLeftDp, y = boxTopDp)
                    .width(boxWidthDp)
                    .height(boxHeightDp)
                    .background(Color.White.copy(alpha = 0.95f), RoundedCornerShape(6.dp))
                    .border(1.dp, Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .clickable { onBubbleClick(bubble) }
                    .padding(horizontal = 2.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = bubble.translatedText,
                    color = Color.Black,
                    fontSize = calculatedSp.sp,
                    lineHeight = (calculatedSp * 1.15f).sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 10,
                )
            }
        }
    }
}
