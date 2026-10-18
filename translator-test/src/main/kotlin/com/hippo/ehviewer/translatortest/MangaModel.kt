package com.hippo.ehviewer.translatortest

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
)

data class MangaPage(
    val index: Int,
    val name: String,
    val uriString: String,
)

data class MangaBook(
    val title: String,
    val pages: List<MangaPage>,
)
