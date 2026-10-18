package com.hippo.ehviewer.translator.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hippo.ehviewer.translator.LocalReaderTranslation
import com.hippo.ehviewer.translator.PageTranslation

/**
 * Renders a page translation with the reader's current style settings and
 * reports bubble edits back to the reader state.
 */
@Composable
fun ReaderTranslationOverlay(
    pageIndex: Int,
    translation: PageTranslation,
    bitmapWidth: Int,
    bitmapHeight: Int,
    fitMode: OverlayFitMode,
    modifier: Modifier = Modifier,
) {
    val state = LocalReaderTranslation.current ?: return
    val settings = state.settings

    TranslationOverlay(
        bubbles = translation.bubbles,
        bitmapWidth = bitmapWidth,
        bitmapHeight = bitmapHeight,
        globalBubbleScale = settings.globalBubbleScale,
        horizontalExpansion = settings.horizontalExpansion,
        globalFontScale = settings.globalFontScale,
        minFontSizeSp = settings.minFontSizeSp,
        maxFontSizeSp = settings.maxFontSizeSp,
        fitTextInsideBubble = settings.fitTextInsideBubble,
        maxHeightExpansion = settings.maxHeightExpansion,
        bubbleOpacity = settings.bubbleOpacity,
        dynamicBubbleSize = settings.dynamicBubbleSize,
        bubbleOrientation = settings.bubbleOrientation,
        bubbleShape = settings.bubbleShape,
        bubblePadding = settings.bubblePadding,
        uppercaseScanlation = settings.uppercaseScanlation,
        preventCollisions = settings.preventCollisions,
        collisionGap = settings.collisionGap,
        collisionIterations = settings.collisionIterations,
        maxCollisionShift = settings.maxCollisionShiftRatio,
        borderThickness = settings.borderThickness,
        fitMode = fitMode,
        isManipulationEnabled = settings.isManipulationMode,
        onBubbleClick = { bubble -> state.editingBubble = pageIndex to bubble },
        onBubbleUpdated = { bubble -> state.updateBubble(pageIndex, bubble) },
        modifier = modifier,
    )
}
