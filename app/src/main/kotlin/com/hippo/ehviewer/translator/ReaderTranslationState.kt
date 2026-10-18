package com.hippo.ehviewer.translator

import android.graphics.Bitmap
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
import com.hippo.ehviewer.image.Image
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val CURRENT_PAGE_TIMEOUT_MS = 30_000L
private const val AHEAD_PAGE_TIMEOUT_MS = 15_000L

/**
 * State of the translation of a single page, shown in the translator dialog.
 */
@Stable
sealed interface TranslationStatus {
    data object Idle : TranslationStatus
    data object WaitingForPage : TranslationStatus
    data object RecognizingText : TranslationStatus
    data object TranslatingText : TranslationStatus
    data object Done : TranslationStatus
    data class Failed(val message: String) : TranslationStatus

    val isBusy: Boolean
        get() = this is WaitingForPage || this is RecognizingText || this is TranslatingText
}

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

    private val statuses = mutableStateMapOf<Int, TranslationStatus>()

    var editingBubble by mutableStateOf<Pair<Int, TranslatedBubble>?>(null)

    private val mutex = Mutex()
    private var lastRevision = settings.revision
    private var generation = 0

    fun status(index: Int): TranslationStatus = statuses[index]
        ?: if (translations.containsKey(index)) TranslationStatus.Done else TranslationStatus.Idle

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
            .forEach {
                translations.remove(it)
                statuses.remove(it)
            }
    }

    fun clear() {
        generation++
        translations.clear()
        statuses.clear()
        editingBubble = null
    }

    /** Forgets a page so the next call re-runs OCR instead of using the cache. */
    fun resetPage(index: Int) {
        translations.remove(index)
        statuses.remove(index)
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

        // Show progress while queued behind another page's OCR too.
        statuses[index] = TranslationStatus.WaitingForPage
        try {
            return mutex.withLock {
                translations[index]?.let {
                    statuses[index] = TranslationStatus.Done
                    return@withLock it
                }
                translateLocked(index, timeoutMs)
            }
        } catch (e: CancellationException) {
            if (statuses[index] == TranslationStatus.WaitingForPage) statuses.remove(index)
            throw e
        }
    }

    private suspend fun translateLocked(index: Int, timeoutMs: Long): PageTranslation? {
        val page = pageLoader.pages.getOrNull(index) ?: return fail(index, "La página no existe")
        val requestGeneration = generation
        val signature = settings.translationSignature()
        try {
            val image = awaitImage(index, timeoutMs) ?: return fail(
                index,
                when (page.statusFlow.value) {
                    is PageStatus.Blocked -> "La página está bloqueada"
                    is PageStatus.Error -> "No se pudo descargar la página"
                    else -> "La imagen no está lista (tiempo de espera agotado)"
                },
            )

            if (!image.pin()) return fail(index, "La imagen se liberó antes de traducir")
            var softwareCopy: Bitmap? = null
            try {
                val bitmap = prepareBitmap(image) { softwareCopy = it } ?: return fail(
                    index,
                    if (image.innerImage is BitmapImage) {
                        "No se pudo preparar la imagen para OCR"
                    } else {
                        "Las páginas animadas (GIF/WebP) no se traducen"
                    },
                )

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
                    onPhase = { phase ->
                        statuses[index] = when (phase) {
                            PageTranslator.Phase.OCR -> TranslationStatus.RecognizingText
                            PageTranslator.Phase.TRANSLATE -> TranslationStatus.TranslatingText
                        }
                    },
                )
                if (requestGeneration == generation && signature == settings.translationSignature()) {
                    translations[index] = result.copy(settingsSignature = signature)
                    statuses[index] = TranslationStatus.Done
                } else {
                    // Settings changed while translating: drop the result so the
                    // next pass re-runs with the new configuration.
                    statuses[index] = TranslationStatus.Idle
                }
                return result
            } finally {
                softwareCopy?.recycle()
                // If the cache was evicted while we were translating this
                // was the last reference: let the loader decode it again
                // next time the page is displayed.
                if (image.unpin()) {
                    pageLoader.notifyPageWait(index)
                }
            }
        } catch (e: CancellationException) {
            statuses[index] = TranslationStatus.Idle
            throw e
        } catch (e: OutOfMemoryError) {
            logcat("ReaderTranslator", e)
            return fail(index, "Memoria insuficiente para traducir esta página")
        } catch (e: Throwable) {
            logcat("ReaderTranslator", e)
            return fail(index, "Error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun awaitImage(index: Int, timeoutMs: Long): Image? {
        val page = pageLoader.pages.getOrNull(index) ?: return null
        (page.statusFlow.value as? PageStatus.Ready)?.image?.let { return it }
        return withTimeoutOrNull(timeoutMs) {
            pageLoader.request(index)
            page.statusFlow.first {
                it is PageStatus.Ready || it is PageStatus.Error || it is PageStatus.Blocked
            }
            (page.statusFlow.value as? PageStatus.Ready)?.image
        }
    }

    /**
     * ML Kit only accepts software ARGB_8888 bitmaps, but the reader decodes
     * pages as HARDWARE bitmaps for rendering. Copy such pages once and let the
     * caller recycle the copy.
     */
    private fun prepareBitmap(image: Image, onCopy: (Bitmap) -> Unit): Bitmap? {
        val source = (image.innerImage as? BitmapImage)?.bitmap ?: return null
        if (source.isRecycled) return null
        if (source.config == Bitmap.Config.ARGB_8888) return source

        val copy = try {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Throwable) {
            logcat("ReaderTranslator", e)
            null
        }
        copy?.let(onCopy)
        return copy
    }

    private fun fail(index: Int, message: String): PageTranslation? {
        statuses[index] = TranslationStatus.Failed(message)
        return null
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
