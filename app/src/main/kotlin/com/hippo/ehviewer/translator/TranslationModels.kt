package com.hippo.ehviewer.translator

import android.graphics.Rect

data class TranslatedBubble(
    val id: Int = 0,
    val originalText: String,
    val translatedText: String,
    val boundingBox: Rect,
    val widthScale: Float = 1.0f,
    val heightScale: Float = 1.0f,
    val customFontSizeSp: Float? = null,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val isVertical: Boolean? = null,
)

data class PageTranslation(
    val pageIndex: Int,
    val bubbles: List<TranslatedBubble>,
    val ocrDurationMs: Long,
    val translateDurationMs: Long,
    val totalDurationMs: Long,
    val engineName: String = "Google",
    /** Signature of the settings used to produce this translation. */
    val settingsSignature: String = "",
    /** Number of OCR boxes the model collapsed into one balloon. */
    val mergedBoxes: Int = 0,
    /** Number of OCR boxes the model discarded as empty or duplicated. */
    val discardedBoxes: Int = 0,
)
