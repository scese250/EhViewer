package com.hippo.ehviewer.translator

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import coil3.BitmapImage
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.gallery.PageStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val CURRENT_PAGE_TIMEOUT_MS = 30_000L
private const val AHEAD_PAGE_TIMEOUT_MS = 15_000L

/**
 * Per-reader translation cache and state.
 *
 * The reader owns one instance while a gallery is open. It is provided to the
 * page composables through [LocalReaderTranslation], so the overlay can render
 * translations without threading a dozen parameters through the pager.
 */
@Stable
class ReaderTranslationState(
    val pageLoader: PageLoader,
    val settings: TranslatorSettings,
) {
    val translations = mutableStateMapOf<Int, PageTranslation>()

    val translatingPages = mutableStateMapOf<Int, Boolean>()

    var editingBubble by mutableStateOf<Pair<Int, TranslatedBubble>?>(null)

    private val mutex = Mutex()
    private var lastRevision = settings.revision
    private var generation = 0

    fun isTranslating(index: Int) = translatingPages[index] == true

    /**
     * Drops cached pages produced with older OCR/translation settings. Pages
     * translated manually after editing the settings keep their result even
     * when [TranslatorSettings.save] bumps the revision on dialog close.
     */
    fun onRevisionChanged() {
        if (lastRevision != settings.revision) {
            lastRevision = settings.revision
            purgeStale()
        }
    }

    private fun purgeStale() {
        val signature = settings.translationSignature()
        translations.keys
            .filter { translations[it]?.settingsSignature != signature }
            .forEach { translations.remove(it) }
    }

    fun clear() {
        generation++
        translations.clear()
        translatingPages.clear()
        editingBubble = null
    }

    fun updateBubble(pageIndex: Int, bubble: TranslatedBubble) {
        val current = translations[pageIndex] ?: return
        translations[pageIndex] = current.copy(
            bubbles = current.bubbles.map { if (it.id == bubble.id) bubble else it },
        )
    }

    /**
     * Translates one page, waiting for its bitmap when it is not decoded yet.
     * Concurrent calls are serialized so the ML Kit recognizer and the API key
     * rotation are not used in parallel.
     */
    suspend fun translatePage(
        index: Int,
        timeoutMs: Long = CURRENT_PAGE_TIMEOUT_MS,
    ): PageTranslation? {
        translations[index]?.let { return it }

        return mutex.withLock {
            translations[index]?.let { return@withLock it }

            val page = pageLoader.pages.getOrNull(index) ?: return@withLock null
            val requestGeneration = generation
            val signature = settings.translationSignature()
            translatingPages[index] = true
            try {
                val image = withTimeoutOrNull(timeoutMs) {
                    pageLoader.request(index)
                    page.statusFlow.first {
                        it is PageStatus.Ready || it is PageStatus.Error || it is PageStatus.Blocked
                    }
                    (page.statusFlow.value as? PageStatus.Ready)?.image
                }
                if (image == null) return@withLock null

                if (!image.pin()) return@withLock null
                try {
                    val bitmap = (image.innerImage as? BitmapImage)?.bitmap ?: return@withLock null
                    if (bitmap.isRecycled) return@withLock null

                    val result = PageTranslator.translatePage(
                        pageIndex = index,
                        bitmap = bitmap,
                        sourceLang = settings.sourceLang,
                        targetLang = settings.targetLang,
                        promptTemplate = settings.geminiPromptTemplate,
                        engine = settings.engine,
                        geminiApiKeys = settings.getGeminiApiKeys(),
                        geminiModel = settings.geminiModel,
                        grouping = settings.groupingConfig(),
                    )
                    if (requestGeneration == generation && signature == settings.translationSignature()) {
                        translations[index] = result.copy(settingsSignature = signature)
                    }
                    result
                } finally {
                    // If the cache was evicted while we were translating this
                    // was the last reference: let the loader decode it again
                    // next time the page is displayed.
                    if (image.unpin()) {
                        pageLoader.notifyPageWait(index)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat("ReaderTranslator", e)
                null
            } finally {
                translatingPages.remove(index)
            }
        }
    }

    /**
     * Translates the current page and up to [maxAhead] following pages.
     * [maxAhead] must already be capped to the configured preload count.
     */
    suspend fun translateAround(index: Int, maxAhead: Int) {
        onRevisionChanged()
        translatePage(index)
        if (maxAhead <= 0) return
        for (offset in 1..maxAhead) {
            val next = index + offset
            if (next >= pageLoader.size) return
            if (translations.containsKey(next)) continue
            translatePage(next, AHEAD_PAGE_TIMEOUT_MS)
        }
    }
}

/**
 * Null outside the reader, which keeps the overlay a no-op in every other
 * screen that reuses [com.hippo.ehviewer.ui.reader.PagerItem].
 */
val LocalReaderTranslation = staticCompositionLocalOf<ReaderTranslationState?> { null }
