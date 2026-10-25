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
        // The model's boxes are always exact now: every layout option that only
        // applied to estimated boxes is gone from the settings.
        exactPlacement = true,
        globalBubbleScale = settings.globalBubbleScale,
        horizontalExpansion = 1.20f,
        globalFontScale = 1f,
        standardFontSizeSp = settings.standardFontSizeSp,
        minFontSizeSp = 5f,
        maxFontSizeSp = settings.standardFontSizeSp,
        fitTextInsideBubble = true,
        maxHeightExpansion = 1.6f,
        showSfx = settings.showSfx,
        bubbleOpacity = settings.bubbleOpacity,
        dynamicBubbleSize = true,
        bubbleOrientation = settings.bubbleOrientation,
        bubbleShape = settings.bubbleShape,
        bubbleStyle = settings.bubbleStyle,
        bubblePadding = settings.bubblePadding,
        uppercaseScanlation = settings.uppercaseScanlation,
        preventCollisions = settings.preventCollisions,
        collisionGap = settings.collisionGap,
        collisionIterations = settings.collisionIterations,
        maxCollisionShift = settings.maxCollisionShiftRatio,
        borderThickness = settings.borderThickness,
        outlineWidth = settings.outlineWidth,
        fitMode = fitMode,
        isManipulationEnabled = settings.isManipulationMode,
        onBubbleUpdated = { bubble -> state.updateBubble(pageIndex, bubble) },
        modifier = modifier,
    )
}
