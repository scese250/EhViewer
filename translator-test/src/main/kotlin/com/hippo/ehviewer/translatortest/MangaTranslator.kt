package com.hippo.ehviewer.translatortest

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray

object MangaTranslator {
    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    suspend fun translatePage(
        pageIndex: Int,
        bitmap: Bitmap,
        targetLang: String = "es",
    ): PageTranslation = withContext(Dispatchers.Default) {
        var visionText: Text
        val ocrTime = measureTimeMillis {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            visionText = recognizer.processImage(inputImage)
        }

        val validBlocks = visionText.textBlocks.filter { block ->
            block.text.isNotBlank() && (block.boundingBox?.width() ?: 0) > 10 && (block.boundingBox?.height() ?: 0) > 10
        }

        var bubbles: List<TranslatedBubble> = emptyList()
        val transTime = measureTimeMillis {
            bubbles = coroutineScope {
                validBlocks.map { block ->
                    async(Dispatchers.IO) {
                        val original = block.text.replace("\n", " ").trim()
                        val translated = translateText(original, targetLang)
                        TranslatedBubble(
                            originalText = original,
                            translatedText = translated,
                            boundingBox = block.boundingBox ?: Rect(),
                        )
                    }
                }.awaitAll()
            }
        }

        PageTranslation(
            pageIndex = pageIndex,
            bubbles = bubbles,
            ocrDurationMs = ocrTime,
            translateDurationMs = transTime,
            totalDurationMs = ocrTime + transTime,
        )
    }

    private suspend fun translateText(text: String, targetLang: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext text
        runCatching {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val urlString = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encoded"
            val url = URI.create(urlString).toURL()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                connectTimeout = 8000
                readTimeout = 8000
            }
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(response)
            val sentences = array.optJSONArray(0) ?: return@runCatching text
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val part = sentences.optJSONArray(i)
                if (part != null && part.length() > 0) {
                    sb.append(part.optString(0))
                }
            }
            sb.toString().trim()
        }.getOrElse { text }
    }

    private suspend fun TextRecognizer.processImage(
        inputImage: InputImage,
    ): Text = suspendCancellableCoroutine { cont ->
        process(inputImage)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
