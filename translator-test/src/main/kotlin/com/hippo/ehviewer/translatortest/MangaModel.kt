package com.hippo.ehviewer.translatortest

import android.graphics.Rect

data class TranslatedBubble(
    val originalText: String,
    val translatedText: String,
    val boundingBox: Rect,
)

data class PageTranslation(
    val pageIndex: Int,
    val bubbles: List<TranslatedBubble>,
    val ocrDurationMs: Long,
    val translateDurationMs: Long,
    val totalDurationMs: Long,
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
