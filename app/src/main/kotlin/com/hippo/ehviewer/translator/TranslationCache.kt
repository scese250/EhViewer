package com.hippo.ehviewer.translator

import android.content.Context
import android.graphics.Rect
import com.ehviewer.core.util.logcat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process wide, disk backed cache of translated pages.
 *
 * The reader builds a fresh [ReaderTranslationState] every time the gallery is
 * opened, so a per-reader map is lost when the user goes back to the gallery
 * and enters again: every page is downloaded, OCR'd and translated once more.
 * This cache survives those trips and, because it is written to the app's own
 * storage, also survives a restart. Entries are keyed by the gallery plus the
 * page index, so two galleries never see each other's pages.
 *
 * Entries keep the settings signature they were produced with. When the engine,
 * the languages or the prompts change, the old entries simply stop matching and
 * are translated again; they are also evicted by the LRU bound.
 */
object TranslationCache {
    /** Pages are cheap (text plus rectangles), but the map must stay bounded. */
    private const val MAX_ENTRIES = 600

    private const val FILE_NAME = "translation-cache.json"
    private const val VERSION = 1
    private const val SAVE_DELAY_MS = 700L

    data class Stats(val pages: Int, val bytes: Long)

    private data class Key(val gallery: String, val pageIndex: Int)

    private val entries = object : LinkedHashMap<Key, PageTranslation>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, PageTranslation>): Boolean = size > MAX_ENTRIES
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private var file: File? = null

    /** Reads the stored pages. Safe to call more than once. */
    @Synchronized
    fun initialize(context: Context) {
        if (file != null) return
        val dir = File(context.filesDir, "translation-cache").apply { mkdirs() }
        file = File(dir, FILE_NAME)
        load()
    }

    @Synchronized
    fun get(gallery: String, pageIndex: Int, signature: String): PageTranslation? {
        val cached = entries[Key(gallery, pageIndex)] ?: return null
        // A page translated with another engine/prompt is not reusable.
        return cached.takeIf { it.settingsSignature == signature }
    }

    @Synchronized
    fun put(gallery: String, pageIndex: Int, translation: PageTranslation) {
        entries[Key(gallery, pageIndex)] = translation
        scheduleSave()
    }

    @Synchronized
    fun remove(gallery: String, pageIndex: Int) {
        entries.remove(Key(gallery, pageIndex))
        scheduleSave()
    }

    @Synchronized
    fun removeAll(gallery: String) {
        entries.keys.removeAll { it.gallery == gallery }
        scheduleSave()
    }

    /** Cached pages of a gallery, used to hydrate the reader instantly. */
    @Synchronized
    fun snapshot(gallery: String, signature: String): Map<Int, PageTranslation> = entries.entries
        .filter { it.key.gallery == gallery && it.value.settingsSignature == signature }
        .associate { it.key.pageIndex to it.value }

    /**
     * Pages stored and bytes on disk. Pending writes are flushed first so the
     * size the dialog shows matches what a restart would find.
     */
    @Synchronized
    fun stats(): Stats {
        if (saveJob?.isActive == true) saveNow()
        return Stats(entries.size, file?.length() ?: 0L)
    }

    /** Deletes every stored page, for every gallery. */
    @Synchronized
    fun clearAll() {
        entries.clear()
        saveJob?.cancel()
        saveJob = null
        file?.delete()
    }

    private fun scheduleSave() {
        if (file == null) return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            saveNow()
        }
    }

    @Synchronized
    private fun saveNow() {
        val target = file ?: return
        saveJob?.cancel()
        saveJob = null
        runCatching {
            target.writeText(encode())
        }.onFailure {
            logcat("TranslationCache", it)
        }
    }

    private fun encode(): String {
        val array = JSONArray()
        entries.forEach { (key, translation) ->
            array.put(
                JSONObject().apply {
                    put("g", key.gallery)
                    put("p", key.pageIndex)
                    put("sig", translation.settingsSignature)
                    put("ocr", translation.ocrDurationMs)
                    put("tr", translation.translateDurationMs)
                    put("total", translation.totalDurationMs)
                    put("engine", translation.engineName)
                    put("merged", translation.mergedBoxes)
                    put("discarded", translation.discardedBoxes)
                    put(
                        "bubbles",
                        JSONArray().apply {
                            translation.bubbles.forEach { bubble ->
                                put(
                                    JSONObject().apply {
                                        put("id", bubble.id)
                                        put("ot", bubble.originalText)
                                        put("tt", bubble.translatedText)
                                        put("l", bubble.boundingBox.left)
                                        put("t", bubble.boundingBox.top)
                                        put("r", bubble.boundingBox.right)
                                        put("b", bubble.boundingBox.bottom)
                                        put("ws", bubble.widthScale.toDouble())
                                        put("hs", bubble.heightScale.toDouble())
                                        // Optional values are omitted, never stored as
                                        // null: JSONObject.has() is true for a null
                                        // and optDouble() turns it into NaN.
                                        bubble.customFontSizeSp?.let { put("fs", it.toDouble()) }
                                        put("ox", bubble.offsetX.toDouble())
                                        put("oy", bubble.offsetY.toDouble())
                                        bubble.isVertical?.let { put("iv", it) }
                                        put("sfx", bubble.isSfx)
                                        put("exact", bubble.exactBox)
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("entries", array)
            .toString()
    }

    private fun load() {
        val target = file ?: return
        if (!target.exists()) return
        runCatching {
            val root = JSONObject(target.readText())
            if (root.optInt("version") != VERSION) return@runCatching
            val array = root.optJSONArray("entries") ?: return@runCatching
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val gallery = item.optString("g")
                if (gallery.isEmpty()) continue
                val translation = decode(item) ?: continue
                entries[Key(gallery, translation.pageIndex)] = translation
            }
        }.onFailure {
            logcat("TranslationCache", it)
        }
    }

    private fun decode(item: JSONObject): PageTranslation? = runCatching {
        val bubbleArray = item.optJSONArray("bubbles") ?: JSONArray()
        val bubbles = ArrayList<TranslatedBubble>(bubbleArray.length())
        for (i in 0 until bubbleArray.length()) {
            val bubble = bubbleArray.optJSONObject(i) ?: continue
            bubbles += TranslatedBubble(
                id = bubble.optInt("id", i),
                originalText = bubble.optString("ot"),
                translatedText = bubble.optString("tt"),
                boundingBox = Rect(
                    bubble.optInt("l"),
                    bubble.optInt("t"),
                    bubble.optInt("r"),
                    bubble.optInt("b"),
                ),
                // NaN/Infinity would poison every measurement in the overlay,
                // so a corrupt value falls back to the neutral one.
                widthScale = bubble.optDouble("ws", 1.0).toFloat()
                    .takeIf { it.isFinite() && it > 0f } ?: 1f,
                heightScale = bubble.optDouble("hs", 1.0).toFloat()
                    .takeIf { it.isFinite() && it > 0f } ?: 1f,
                // Older files stored a JSON null here, which reads back as NaN;
                // a NaN font size used to crash the overlay while laying out.
                customFontSizeSp = bubble.optDouble("fs").toFloat()
                    .takeIf { bubble.has("fs") && !bubble.isNull("fs") && it.isFinite() && it > 0f },
                offsetX = bubble.optDouble("ox", 0.0).toFloat().takeIf { it.isFinite() } ?: 0f,
                offsetY = bubble.optDouble("oy", 0.0).toFloat().takeIf { it.isFinite() } ?: 0f,
                isVertical = if (bubble.has("iv") && !bubble.isNull("iv")) bubble.optBoolean("iv") else null,
                isSfx = bubble.optBoolean("sfx", false),
                exactBox = bubble.optBoolean("exact", false),
            )
        }
        PageTranslation(
            pageIndex = item.optInt("p"),
            bubbles = bubbles,
            ocrDurationMs = item.optLong("ocr"),
            translateDurationMs = item.optLong("tr"),
            totalDurationMs = item.optLong("total"),
            engineName = item.optString("engine", "Google"),
            settingsSignature = item.optString("sig"),
            mergedBoxes = item.optInt("merged"),
            discardedBoxes = item.optInt("discarded"),
        )
    }.getOrNull()
}
