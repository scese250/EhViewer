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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val CURRENT_PAGE_TIMEOUT_MS = 30_000L
private const val AHEAD_PAGE_TIMEOUT_MS = 15_000L
private const val MAX_RETRANSLATE_PAGES = 30

/**
 * State of the translation of a single page, shown in the translator dialog.
 */
@Stable
sealed interface TranslationStatus {
    data object Idle : TranslationStatus
    data object WaitingForPage : TranslationStatus
    data object RecognizingText : TranslationStatus
    data object ReadingPage : TranslationStatus
    data object TranslatingText : TranslationStatus
    data object Done : TranslationStatus

    /**
     * [rawResponse] is the API reply verbatim; the reader shows it as the
     * reason instead of a fixed message.
     */
    data class Failed(
        val message: String,
        val rawResponse: String? = null,
    ) : TranslationStatus

    val isBusy: Boolean
        get() = this is WaitingForPage || this is RecognizingText ||
            this is ReadingPage || this is TranslatingText
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

    /**
     * Identity of this gallery inside [TranslationCache]. The reader state is
     * recreated on every visit, so the cache lives outside it.
     */
    private val galleryKey: String = pageLoader.info
        ?.let { "gallery:${it.gid}:${pageLoader.size}" }
        ?: "archive:${pageLoader.title}:${pageLoader.size}"

    init {
        // Re-entering a gallery shows the pages translated last time instantly.
        TranslationCache.snapshot(galleryKey, settings.translationSignature())
            .forEach { (index, translation) ->
                translations[index] = translation
                statuses[index] = TranslationStatus.Done
            }
    }

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
        // The cache is keyed by signature too, but a settings change must also
        // free the old pages instead of keeping them until the LRU evicts them.
        TranslationCache.removeAll(galleryKey)
    }

    fun clear() {
        generation++
        translations.clear()
        statuses.clear()
        editingBubble = null
        TranslationCache.removeAll(galleryKey)
    }

    /** Forgets a page so the next call re-runs OCR instead of using the cache. */
    fun resetPage(index: Int) {
        translations.remove(index)
        statuses.remove(index)
        TranslationCache.remove(galleryKey, index)
    }

    fun updateBubble(pageIndex: Int, bubble: TranslatedBubble) {
        val current = translations[pageIndex] ?: return
        val updated = current.copy(
            bubbles = current.bubbles.map { if (it.id == bubble.id) bubble else it },
        )
        translations[pageIndex] = updated
        // Keep the edited bubble when the user comes back to the gallery.
        TranslationCache.put(galleryKey, pageIndex, updated)
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
        cached(index)?.let { return it }

        // Show progress while queued behind another page's OCR too.
        statuses[index] = TranslationStatus.WaitingForPage
        try {
            return mutex.withLock {
                translations[index]?.let {
                    statuses[index] = TranslationStatus.Done
                    return@withLock it
                }
                // Another page's translation may have filled the cache while
                // this one waited for the lock.
                cached(index)?.let { return@withLock it }
                translateLocked(index, timeoutMs)
            }
        } catch (e: CancellationException) {
            if (statuses[index] == TranslationStatus.WaitingForPage) statuses.remove(index)
            throw e
        }
    }

    private suspend fun translateLocked(index: Int, timeoutMs: Long): PageTranslation? {
        val page = pageLoader.pages.getOrNull(index) ?: return fail(index, "The page does not exist")
        val requestGeneration = generation
        val signature = settings.translationSignature()
        try {
            val image = awaitImage(index, timeoutMs) ?: return fail(
                index,
                when (page.statusFlow.value) {
                    is PageStatus.Blocked -> "The page is blocked"
                    is PageStatus.Error -> "The page could not be downloaded"
                    else -> "The image is not ready (timed out)"
                },
            )

            if (!image.pin()) return fail(index, "The image was released before translating")
            var softwareCopy: Bitmap? = null
            try {
                val bitmap = prepareBitmap(image) { softwareCopy = it } ?: return fail(
                    index,
                    if (image.innerImage is BitmapImage) {
                        "The image could not be prepared for the AI"
                    } else {
                        "Animated pages (GIF/WebP) are not translated"
                    },
                )

                val result = PageTranslator.translatePage(
                    pageIndex = index,
                    bitmap = bitmap,
                    sourceLang = settings.sourceLang,
                    targetLang = settings.targetLang,
                    systemPrompt = settings.geminiSystemPrompt,
                    userPrompt = settings.geminiUserPrompt,
                    geminiApiKeys = settings.getGeminiApiKeys(),
                    geminiModel = settings.geminiModel,
                    thinkingLevel = settings.thinkingLevel,
                    onPhase = { phase ->
                        statuses[index] = when (phase) {
                            PageTranslator.Phase.OCR -> TranslationStatus.RecognizingText
                            PageTranslator.Phase.VISION -> TranslationStatus.ReadingPage
                            PageTranslator.Phase.TRANSLATE -> TranslationStatus.TranslatingText
                        }
                    },
                )
                if (requestGeneration == generation && signature == settings.translationSignature()) {
                    val stored = result.copy(settingsSignature = signature)
                    translations[index] = stored
                    TranslationCache.put(galleryKey, index, stored)
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
            return fail(index, "Not enough memory to translate this page")
        } catch (e: Throwable) {
            logcat("ReaderTranslator", e)
            return fail(
                index = index,
                message = e.message ?: e.javaClass.simpleName,
                rawResponse = (e as? TranslationFailure)?.rawResponse,
            )
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

    /** Cached page for this reader, if it was translated with the same settings. */
    private fun cached(index: Int): PageTranslation? = TranslationCache
        .get(galleryKey, index, settings.translationSignature())
        ?.also {
            translations[index] = it
            statuses[index] = TranslationStatus.Done
        }

    private fun fail(
        index: Int,
        message: String,
        rawResponse: String? = null,
    ): PageTranslation? {
        statuses[index] = TranslationStatus.Failed(message, rawResponse)
        return null
    }

    /**
     * Forgets and re-translates the [count] pages after [index], so the next
     * pages the user reads carry a fresh translation (new prompt, new bubbles).
     */
    suspend fun retranslateFollowing(index: Int, count: Int) {
        onRevisionChanged()
        var pending = count.coerceIn(1, MAX_RETRANSLATE_PAGES)
        var next = index + 1
        while (pending > 0 && next < pageLoader.size) {
            resetPage(next)
            translatePage(next)
            next++
            pending--
        }
    }

    /**
     * Keeps the pages around the reader translated while the user reads.
     *
     * The window slides with the current page instead of being restarted on
     * every swipe: the translation in flight is allowed to finish and the next
     * candidate is always the nearest untranslated page inside
     * [current, current + maxAhead]. Turning from page 1 to page 2 therefore
     * ends up translating page 5 once 3 and 4 are done, instead of throwing the
     * batch away and never catching up.
     *
     * [currentPages] is collected for the whole life of the reader; the current
     * page is only read from it, never used to cancel work.
     */
    suspend fun autoTranslate(currentPages: Flow<Int>, maxAhead: Int) {
        onRevisionChanged()
        val target = MutableStateFlow<Int?>(null)
        coroutineScope {
            launch { currentPages.collect { target.value = it } }
            var lastPage = -1
            var currentFailed = false
            while (isActive) {
                val current = target.value
                if (current == null) {
                    delay(50)
                    continue
                }
                if (current != lastPage) {
                    lastPage = current
                    // Arriving at a page is the moment to retry it, even if it
                    // failed before; the pages ahead stay skipped.
                    currentFailed = false
                }
                val next = nextPageToTranslate(current, maxAhead, skipCurrent = currentFailed)
                if (next == null) {
                    // Everything in the window is done; poll slowly so a page
                    // invalidated by a setting change is picked up again.
                    delay(400)
                    continue
                }
                if (next == current) {
                    translatePage(current)
                    currentFailed = statuses[current] is TranslationStatus.Failed
                } else {
                    translatePage(next, AHEAD_PAGE_TIMEOUT_MS)
                }
            }
        }
    }

    private fun nextPageToTranslate(current: Int, maxAhead: Int, skipCurrent: Boolean): Int? {
        val last = (current + maxAhead).coerceAtMost(pageLoader.size - 1)
        for (index in current..last) {
            if (index == current && skipCurrent) continue
            if (translations.containsKey(index)) continue
            // A page that already failed (blocked, missing, unreadable) is not
            // retried on every pass: that would hammer the OCR/API for nothing.
            if (index != current && statuses[index] is TranslationStatus.Failed) continue
            return index
        }
        return null
    }
}

/**
 * Null outside the reader, which keeps the overlay a no-op in every other
 * screen that reuses [com.hippo.ehviewer.ui.reader.PagerItem].
 */
val LocalReaderTranslation = staticCompositionLocalOf<ReaderTranslationState?> { null }
