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

/** A page ahead may still be downloading; waiting for it does not block the rest. */
private const val AHEAD_PAGE_TIMEOUT_MS = 30_000L
private const val MAX_RETRANSLATE_PAGES = 30

/** Answers with no bubbles a page gets before it is accepted as textless. */
private const val MAX_EMPTY_ATTEMPTS = 2

/** Automatic retries of a page that failed, before the reader has to ask again. */
private const val MAX_FAILED_RETRIES = 2

/** Cooldowns, so a page that is not ready is not asked again in a tight loop. */
private const val EMPTY_RETRY_DELAY_MS = 2_000L
private const val FAILED_RETRY_DELAY_MS = 10_000L
private const val AHEAD_POLL_MS = 150L

/** Progress of the manual "redo translation" batch. */
data class RetranslateProgress(val done: Int, val total: Int)

/**
 * State of the translation of a single page, shown in the translator dialog.
 */
@Stable
sealed interface TranslationStatus {
    data object Idle : TranslationStatus
    data object WaitingForPage : TranslationStatus
    data object ReadingPage : TranslationStatus
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
        get() = this is WaitingForPage || this is ReadingPage
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

    /** Non-null while the manual "redo translation" batch is running. */
    var retranslateProgress by mutableStateOf<RetranslateProgress?>(null)
        private set

    private val statuses = mutableStateMapOf<Int, TranslationStatus>()

    /**
     * Failures the reader already closed while reading. The status keeps the raw
     * reply for the settings dialog, but the banner does not come back when the
     * page is turned again.
     */
    private val dismissedErrors = mutableStateMapOf<Int, Unit>()

    fun isErrorDismissed(index: Int): Boolean = dismissedErrors.containsKey(index)

    fun dismissError(index: Int) {
        dismissedErrors[index] = Unit
    }

    /**
     * One lock per page: two coroutines asking for the same page share the
     * result instead of paying for it twice, while different pages translate in
     * parallel, one per API key.
     */
    private val pageLocks = mutableMapOf<Int, Mutex>()
    private val pageLocksGuard = Mutex()

    /** Pages that answered nothing: retried a couple of times, then accepted. */
    private val emptyAttempts = mutableMapOf<Int, Int>()

    /** Pages that failed and still have an automatic retry left. */
    private val failedRetries = mutableMapOf<Int, Int>()

    /** Earliest moment to ask for a page again, so a slow page is not hammered. */
    private val retryAt = mutableMapOf<Int, Long>()

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
        TranslationCache.snapshot(galleryKey)
            .forEach { (index, translation) ->
                translations[index] = translation
                statuses[index] = TranslationStatus.Done
            }
    }

    fun status(index: Int): TranslationStatus = statuses[index]
        ?: if (translations.containsKey(index)) TranslationStatus.Done else TranslationStatus.Idle

    fun clear() {
        generation++
        translations.clear()
        statuses.clear()
        dismissedErrors.clear()
        emptyAttempts.clear()
        failedRetries.clear()
        retryAt.clear()
        TranslationCache.removeAll(galleryKey)
    }

    /** Forgets a page so the next call re-runs OCR instead of using the cache. */
    fun resetPage(index: Int) {
        translations.remove(index)
        statuses.remove(index)
        // A new attempt after "Translate again" is allowed to report its failure
        // again: only the dismissals of pages nobody asked to retry are kept.
        dismissedErrors.remove(index)
        emptyAttempts.remove(index)
        failedRetries.remove(index)
        retryAt.remove(index)
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
     *
     * Pages translate in parallel: each one waits for its own API key instead of
     * queueing behind the others.
     */
    suspend fun translatePage(
        index: Int,
        timeoutMs: Long = CURRENT_PAGE_TIMEOUT_MS,
    ): PageTranslation? {
        if (isSettled(index)) return translations[index]
        cached(index)?.takeIf { it.bubbles.isNotEmpty() }?.let { return it }

        // Show progress while the page is still downloading.
        statuses[index] = TranslationStatus.WaitingForPage
        try {
            return lockFor(index).withLock {
                if (isSettled(index)) {
                    translations[index]?.let { return@withLock it }
                }
                // Another coroutine may have filled the cache while this one
                // waited for its turn.
                cached(index)?.takeIf { it.bubbles.isNotEmpty() }?.let { return@withLock it }
                translateLocked(index, timeoutMs)
            }
        } catch (e: CancellationException) {
            if (statuses[index] == TranslationStatus.WaitingForPage) statuses.remove(index)
            throw e
        }
    }

    /**
     * True when the page has an answer and must not be asked again: a page with
     * bubbles, or one that stayed empty after all of its attempts.
     */
    private fun isSettled(index: Int): Boolean {
        val stored = translations[index] ?: return false
        if (stored.bubbles.isNotEmpty()) return true
        return (emptyAttempts[index] ?: 0) >= MAX_EMPTY_ATTEMPTS
    }

    private suspend fun lockFor(index: Int): Mutex = pageLocksGuard.withLock {
        pageLocks.getOrPut(index) { Mutex() }
    }

    private suspend fun translateLocked(index: Int, timeoutMs: Long): PageTranslation? {
        val page = pageLoader.pages.getOrNull(index) ?: return fail(index, "The page does not exist")
        val requestGeneration = generation
        try {
            val image = awaitImage(index, timeoutMs) ?: return fail(
                index,
                when (page.statusFlow.value) {
                    is PageStatus.Blocked -> "The page is blocked"
                    is PageStatus.Error -> "The page could not be downloaded"
                    else -> "The image is not ready (timed out)"
                },
                // A page that is still downloading is worth another try; a page
                // the site blocked is not.
                retryable = page.statusFlow.value !is PageStatus.Blocked,
            )

            if (!image.pin()) return fail(index, "The image was released before translating", retryable = true)
            var softwareCopy: Bitmap? = null
            try {
                val bitmap = prepareBitmap(image) { softwareCopy = it } ?: return fail(
                    index,
                    if (image.innerImage is BitmapImage) {
                        "The image could not be prepared for the AI"
                    } else {
                        "Animated pages (GIF/WebP) are not translated"
                    },
                    retryable = image.innerImage is BitmapImage,
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
                            PageTranslator.Phase.VISION -> TranslationStatus.ReadingPage
                        }
                    },
                )
                if (requestGeneration == generation) {
                    translations[index] = result
                    if (result.bubbles.isEmpty()) {
                        // Nothing was read: the page is never cached, and the
                        // scheduler asks again shortly instead of leaving a page
                        // the model skipped blank for the rest of the read.
                        emptyAttempts[index] = (emptyAttempts[index] ?: 0) + 1
                        retryAt[index] = System.currentTimeMillis() + EMPTY_RETRY_DELAY_MS
                        TranslationCache.remove(galleryKey, index)
                    } else {
                        emptyAttempts.remove(index)
                        failedRetries.remove(index)
                        retryAt.remove(index)
                        TranslationCache.put(galleryKey, index, result)
                    }
                    statuses[index] = TranslationStatus.Done
                    dismissedErrors.remove(index)
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
                // A dropped connection or a model that answered garbage is worth
                // another try; fail() stops after a couple of them.
                retryable = true,
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
     * The page has to be drawn and JPEG-encoded before it goes to the model,
     * but the reader decodes pages as HARDWARE bitmaps for rendering. Copy such
     * pages once to a software ARGB_8888 bitmap and let the caller recycle it.
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

    /** Page stored for this reader on a previous visit, if there is one. */
    private fun cached(index: Int): PageTranslation? = TranslationCache
        .get(galleryKey, index)
        ?.also {
            translations[index] = it
            statuses[index] = TranslationStatus.Done
        }

    /**
     * Records the failure. A retryable one is asked again after a cooldown, up to
     * [MAX_FAILED_RETRIES] times, so a page whose request died does not stay
     * untranslated for the rest of the read.
     */
    private fun fail(
        index: Int,
        message: String,
        rawResponse: String? = null,
        retryable: Boolean = false,
    ): PageTranslation? {
        statuses[index] = TranslationStatus.Failed(message, rawResponse)
        val attempts = failedRetries[index] ?: 0
        if (retryable && attempts < MAX_FAILED_RETRIES) {
            failedRetries[index] = attempts + 1
            retryAt[index] = System.currentTimeMillis() + FAILED_RETRY_DELAY_MS
        } else {
            // Out of tries (or not worth one): the page waits for the reader to
            // reach it again instead of being asked on every pass.
            failedRetries.remove(index)
            retryAt.remove(index)
        }
        return null
    }

    /**
     * Forgets and re-translates the [count] pages after [index], so the next
     * pages the user reads carry a fresh translation (new prompt, new bubbles).
     */
    suspend fun retranslateFollowing(index: Int, count: Int) {
        if (retranslateProgress != null) return
        val available = (pageLoader.size - index - 1).coerceAtLeast(0)
        val total = minOf(count.coerceIn(1, MAX_RETRANSLATE_PAGES), available)
        if (total == 0) return
        var pending = total
        var next = index + 1
        retranslateProgress = RetranslateProgress(0, total)
        try {
            while (pending > 0 && next < pageLoader.size) {
                resetPage(next)
                translatePage(next)
                next++
                pending--
                retranslateProgress = RetranslateProgress(total - pending, total)
            }
        } finally {
            retranslateProgress = null
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
        val target = MutableStateFlow<Int?>(null)
        // One page per API key: the keys split the window between them, so the
        // pages ahead translate in parallel instead of one after another.
        val parallel = settings.getGeminiApiKeys().size.coerceAtLeast(1)
        coroutineScope {
            launch { currentPages.collect { target.value = it } }
            val inFlight = mutableSetOf<Int>()
            var lastPage = -1
            while (isActive) {
                val current = target.value
                if (current == null) {
                    delay(50)
                    continue
                }
                if (current != lastPage) {
                    lastPage = current
                    // Reaching a page is the moment to try it again, even when it
                    // failed before; the pages ahead keep their cooldowns.
                    resetRetries(current)
                }
                while (inFlight.size < parallel) {
                    val next = nextPageToTranslate(current, maxAhead, inFlight) ?: break
                    inFlight += next
                    launch {
                        try {
                            translatePage(
                                index = next,
                                timeoutMs = if (next <= current) CURRENT_PAGE_TIMEOUT_MS else AHEAD_PAGE_TIMEOUT_MS,
                            )
                        } finally {
                            inFlight -= next
                        }
                    }
                }
                delay(AHEAD_POLL_MS)
            }
        }
    }

    /** Forgets the failures of the page the reader just landed on. */
    private fun resetRetries(index: Int) {
        if (statuses[index] !is TranslationStatus.Failed) return
        statuses.remove(index)
        failedRetries.remove(index)
        retryAt.remove(index)
    }

    private fun nextPageToTranslate(current: Int, maxAhead: Int, inFlight: Set<Int>): Int? {
        val last = (current + maxAhead).coerceAtMost(pageLoader.size - 1)
        val now = System.currentTimeMillis()
        for (index in current..last) {
            if (index in inFlight) continue
            if (isSettled(index)) continue
            // A page waiting for its cooldown (slow download, empty answer) is
            // picked up as soon as it expires.
            if (now < (retryAt[index] ?: 0L)) continue
            // A page that failed for good (blocked, missing, no key) waits for
            // the reader to reach it again instead of being asked every pass.
            if (statuses[index] is TranslationStatus.Failed && index !in failedRetries) continue
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
