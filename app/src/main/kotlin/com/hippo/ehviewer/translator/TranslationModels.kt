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
    /**
     * Onomatopoeia read from the artwork (not from a balloon). It is drawn
     * horizontally at the standard size with no white bubble, so a page full of
     * sound effects does not get covered with boxes. It is only drawn when the
     * reader has sound effects enabled.
     */
    val isSfx: Boolean = false,
    /**
     * The rectangle was measured by the vision model over the text, not derived
     * from the OCR blocks, so it can be trusted as the exact text area.
     */
    val exactBox: Boolean = false,
)

data class PageTranslation(
    val pageIndex: Int,
    val bubbles: List<TranslatedBubble>,
    val ocrDurationMs: Long,
    val translateDurationMs: Long,
    val totalDurationMs: Long,
    val engineName: String = "Gemini",
    /** Signature of the settings used to produce this translation. */
    val settingsSignature: String = "",
    /** Number of OCR boxes the model collapsed into one balloon. */
    val mergedBoxes: Int = 0,
    /** Number of OCR boxes the model discarded as empty or duplicated. */
    val discardedBoxes: Int = 0,
    /** Width of the Bitmap used when this translation was produced. Used to rescale boxes on cache hit. */
    val bitmapWidth: Int = 0,
    /** Height of the Bitmap used when this translation was produced. */
    val bitmapHeight: Int = 0,
)
