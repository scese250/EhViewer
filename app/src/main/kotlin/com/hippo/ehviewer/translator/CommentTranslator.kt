package com.hippo.ehviewer.translator

import androidx.core.text.parseAsHtml
import com.ehviewer.core.model.GalleryComment
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray

object CommentTranslator {
    private val semaphore = Semaphore(4)
    private val translationCache = ConcurrentHashMap<String, String>()

    fun containsAsianCharacters(text: String): Boolean = text.any { ch ->
        when (ch.code) {
            in 0x2E80..0x2EFF, // CJK Radicals
            in 0x3040..0x309F, // Hiragana
            in 0x30A0..0x30FF, // Katakana
            in 0x3100..0x312F, // Bopomofo
            in 0x31A0..0x31BF, // Bopomofo Extended
            in 0x31F0..0x31FF, // Katakana Phonetic Extensions
            in 0x3130..0x318F, // Hangul Compatibility Jamo
            in 0x3400..0x4DBF, // CJK Extension A
            in 0x4E00..0x9FFF, // CJK Unified Ideographs
            in 0xAC00..0xD7AF, // Hangul Syllables
            in 0x1100..0x11FF, // Hangul Jamo
            in 0xF900..0xFAFF,
            -> true // CJK Compatibility Ideographs
            else -> false
        }
    }

    suspend fun translateComment(comment: GalleryComment, targetLang: String = "en"): String? {
        val plainText = comment.comment.parseAsHtml().toString().trim()
        if (plainText.isBlank() || !containsAsianCharacters(plainText)) {
            return null
        }
        return translateText(plainText, targetLang)
    }

    suspend fun translateText(text: String, targetLang: String = "en"): String? = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return@withContext null

        val cached = translationCache[trimmed]
        if (cached != null) return@withContext cached

        val translated = semaphore.withPermit {
            // 1. Primary Google endpoint
            val primary = runCatching {
                val encoded = URLEncoder.encode(trimmed, "UTF-8")
                val urlString = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encoded"
                val url = URI.create(urlString).toURL()
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val array = JSONArray(response)
                val sentences = array.optJSONArray(0) ?: return@runCatching null
                val sb = StringBuilder()
                for (i in 0 until sentences.length()) {
                    val part = sentences.optJSONArray(i)
                    if (part != null && part.length() > 0) {
                        sb.append(part.optString(0))
                    }
                }
                sb.toString().trim()
            }.getOrNull()

            if (!primary.isNullOrBlank()) {
                return@withPermit decodeHtmlEntities(primary)
            }

            // 2. Secondary Google fallback endpoint
            val secondary = runCatching {
                val encoded = URLEncoder.encode(trimmed, "UTF-8")
                val urlString = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=$targetLang&q=$encoded"
                val url = URI.create(urlString).toURL()
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val array = JSONArray(response)
                array.optString(0).trim()
            }.getOrNull()

            if (!secondary.isNullOrBlank()) {
                decodeHtmlEntities(secondary)
            } else {
                null
            }
        }

        if (translated != null) {
            translationCache[trimmed] = translated
        }
        translated
    }

    private fun decodeHtmlEntities(text: String): String = runCatching {
        text.parseAsHtml().toString()
    }.getOrDefault(text)

    suspend fun translateCommentsBatch(
        comments: List<GalleryComment>,
        existingTranslations: Map<Long, String> = emptyMap(),
        targetLang: String = "en",
    ): Map<Long, String> = coroutineScope {
        val needed = comments.filter { comment ->
            if (existingTranslations.containsKey(comment.id)) return@filter false
            val plain = comment.comment.parseAsHtml().toString().trim()
            containsAsianCharacters(plain)
        }
        val deferreds = needed.map { comment ->
            async {
                val translated = translateComment(comment, targetLang)
                if (translated != null) comment.id to translated else null
            }
        }
        existingTranslations + deferreds.awaitAll().filterNotNull().toMap()
    }
}
