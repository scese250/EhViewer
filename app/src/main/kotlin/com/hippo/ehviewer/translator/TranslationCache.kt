package com.hippo.ehviewer.translator

/**
 * Process wide cache of translated pages.
 *
 * The reader builds a fresh [ReaderTranslationState] every time the gallery is
 * opened, so a per-reader map is lost when the user goes back to the gallery
 * and enters again: every page is downloaded, OCR'd and translated once more.
 * This cache survives those trips (it lives as long as the process) and is
 * keyed by the gallery plus the page index, so two different galleries never
 * see each other's pages.
 *
 * Entries keep the settings signature they were produced with. When the user
 * changes the engine, the languages or the prompts, the old entries simply stop
 * matching and are translated again; they are evicted by the LRU bound.
 */
object TranslationCache {
    /** Pages are cheap (text plus rectangles), but the map must stay bounded. */
    private const val MAX_ENTRIES = 600

    private data class Key(val gallery: String, val pageIndex: Int)

    private val entries = object : LinkedHashMap<Key, PageTranslation>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, PageTranslation>): Boolean = size > MAX_ENTRIES
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
    }

    @Synchronized
    fun remove(gallery: String, pageIndex: Int) {
        entries.remove(Key(gallery, pageIndex))
    }

    @Synchronized
    fun removeAll(gallery: String) {
        entries.keys.removeAll { it.gallery == gallery }
    }

    /** Cached pages of a gallery, used to hydrate the reader instantly. */
    @Synchronized
    fun snapshot(gallery: String, signature: String): Map<Int, PageTranslation> = entries.entries
        .filter { it.key.gallery == gallery && it.value.settingsSignature == signature }
        .associate { it.key.pageIndex to it.value }
}
