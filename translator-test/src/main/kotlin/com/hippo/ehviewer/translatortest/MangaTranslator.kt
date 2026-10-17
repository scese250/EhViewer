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
import org.json.JSONObject

object MangaTranslator {
    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    @Volatile
    private var currentKeyIndex = 0

    suspend fun translatePage(
        pageIndex: Int,
        bitmap: Bitmap,
        targetLang: String = "es",
        engine: String = TranslatorSettings.ENGINE_GOOGLE,
        geminiApiKeys: List<String> = emptyList(),
        geminiModel: String = TranslatorSettings.DEFAULT_GEMINI_MODEL,
    ): PageTranslation = withContext(Dispatchers.Default) {
        var visionText: Text
        val ocrTime = measureTimeMillis {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            visionText = recognizer.processImage(inputImage)
        }

        val rawBlocks = visionText.textBlocks.filter { block ->
            block.text.isNotBlank() && (block.boundingBox?.width() ?: 0) > 8 && (block.boundingBox?.height() ?: 0) > 8
        }.map { block ->
            RawBlock(
                text = block.text.replace("\n", " ").trim(),
                rect = block.boundingBox ?: Rect(),
            )
        }

        // Merge adjacent columns in Japanese vertical manga speech bubbles
        val mergedBlocks = mergeAdjacentMangaBlocks(rawBlocks)

        var bubbles: List<TranslatedBubble> = emptyList()
        var engineUsed = "Google"

        val transTime = measureTimeMillis {
            val texts = mergedBlocks.map { it.text }
            if (engine == TranslatorSettings.ENGINE_GEMINI && geminiApiKeys.isNotEmpty()) {
                engineUsed = "Gemini (${geminiModel.removePrefix("gemini-")})"
                try {
                    val translatedTexts = translateWithGemini(
                        texts = texts,
                        targetLang = targetLang,
                        apiKeys = geminiApiKeys,
                        model = geminiModel,
                    )
                    bubbles = mergedBlocks.mapIndexed { idx, block ->
                        TranslatedBubble(
                            id = idx,
                            originalText = block.text,
                            translatedText = translatedTexts.getOrElse(idx) { block.text },
                            boundingBox = block.rect,
                        )
                    }
                } catch (e: Exception) {
                    // Fallback to Google if Gemini fails
                    engineUsed = "Google (Fallback)"
                    bubbles = translateWithGoogleBatch(mergedBlocks, targetLang)
                }
            } else {
                bubbles = translateWithGoogleBatch(mergedBlocks, targetLang)
            }
        }

        PageTranslation(
            pageIndex = pageIndex,
            bubbles = bubbles,
            ocrDurationMs = ocrTime,
            translateDurationMs = transTime,
            totalDurationMs = ocrTime + transTime,
            engineName = engineUsed,
        )
    }

    private suspend fun translateWithGoogleBatch(
        blocks: List<RawBlock>,
        targetLang: String,
    ): List<TranslatedBubble> = coroutineScope {
        blocks.mapIndexed { idx, block ->
            async(Dispatchers.IO) {
                val translated = translateTextGoogle(block.text, targetLang)
                TranslatedBubble(
                    id = idx,
                    originalText = block.text,
                    translatedText = translated,
                    boundingBox = block.rect,
                )
            }
        }.awaitAll()
    }

    private suspend fun translateTextGoogle(text: String, targetLang: String): String = withContext(Dispatchers.IO) {
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

    private suspend fun translateWithGemini(
        texts: List<String>,
        targetLang: String,
        apiKeys: List<String>,
        model: String,
    ): List<String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        if (apiKeys.isEmpty()) error("No hay claves de API disponibles")

        val langName = if (targetLang.equals("es", ignoreCase = true)) "Spanish" else "English"

        val inputItems = JSONArray()
        texts.forEachIndexed { idx, txt ->
            inputItems.put(
                JSONObject().apply {
                    put("id", idx)
                    put("text", txt)
                },
            )
        }

        val prompt = """
            You are an expert manga translator. Translate the following Japanese manga dialogue lines into natural, expressive, and concise $langName suitable for speech bubbles.
            Maintain context and speaker tone between lines on the same page.
            Return ONLY a valid JSON array of objects with "id" and "translated".
            Example: [{"id": 0, "translated": "¡Hola!"}]

            Input dialogue:
            $inputItems
        """.trimIndent()

        val requestJson = JSONObject().apply {
            val parts = JSONArray().apply {
                put(JSONObject().apply { put("text", prompt) })
            }
            val contents = JSONArray().apply {
                put(JSONObject().apply { put("parts", parts) })
            }
            put("contents", contents)
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.2)
                    put("responseMimeType", "application/json")
                    put(
                        "thinkingConfig",
                        JSONObject().apply {
                            put("thinkingLevel", "MINIMAL")
                        },
                    )
                },
            )
        }

        var lastError: Exception? = null
        val totalKeys = apiKeys.size

        for (attempt in 0 until totalKeys) {
            val keyIndex = (currentKeyIndex + attempt) % totalKeys
            val apiKey = apiKeys[keyIndex]

            try {
                val urlString = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val url = URI.create(urlString).toURL()
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 12000
                    readTimeout = 12000
                    doOutput = true
                }

                conn.outputStream.use { os ->
                    os.write(requestJson.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    currentKeyIndex = keyIndex
                    val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(responseBody)
                    val candidates = root.getJSONArray("candidates")
                    val content = candidates.getJSONObject(0).getJSONObject("content")
                    val part = content.getJSONArray("parts").getJSONObject(0)
                    val rawJson = part.getString("text").trim()

                    val results = arrayOfNulls<String>(texts.size)
                    val parsedArray = JSONArray(rawJson)
                    for (i in 0 until parsedArray.length()) {
                        val obj = parsedArray.getJSONObject(i)
                        val id = obj.getInt("id")
                        val trans = obj.getString("translated")
                        if (id in results.indices) {
                            results[id] = trans
                        }
                    }

                    return@withContext texts.mapIndexed { idx, orig ->
                        results[idx] ?: orig
                    }
                } else {
                    val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    lastError = IllegalStateException("Gemini API error ($responseCode) clave #${keyIndex + 1}: $errorText")
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: IllegalStateException("Todas las claves de API fallaron")
    }

    suspend fun testGeminiConnection(apiKeys: List<String>, model: String): Result<String> = withContext(Dispatchers.IO) {
        if (apiKeys.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No has ingresado ninguna clave de API"))
        }

        var successCount = 0
        val errors = mutableListOf<String>()

        for ((index, key) in apiKeys.withIndex()) {
            runCatching {
                val prompt = "Reply with 'OK'."
                val requestJson = JSONObject().apply {
                    val parts = JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    }
                    val contents = JSONArray().apply {
                        put(JSONObject().apply { put("parts", parts) })
                    }
                    put("contents", contents)
                }
                val urlString = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
                val url = URI.create(urlString).toURL()
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                }
                conn.outputStream.use { os ->
                    os.write(requestJson.toString().toByteArray(Charsets.UTF_8))
                }
                if (conn.responseCode == 200) {
                    successCount++
                } else {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Error ${conn.responseCode}"
                    errors.add("Clave #${index + 1}: $err")
                }
            }.onFailure {
                errors.add("Clave #${index + 1}: ${it.message}")
            }
        }

        if (successCount > 0) {
            Result.success("$successCount de ${apiKeys.size} claves funcionan correctamente con $model")
        } else {
            Result.failure(IllegalStateException(errors.joinToString("\n")))
        }
    }

    private fun mergeAdjacentMangaBlocks(blocks: List<RawBlock>): List<RawBlock> {
        if (blocks.size <= 1) return blocks
        val remaining = blocks.toMutableList()
        var changed = true

        while (changed) {
            changed = false
            var i = 0
            while (i < remaining.size) {
                var j = i + 1
                while (j < remaining.size) {
                    val b1 = remaining[i]
                    val b2 = remaining[j]

                    if (shouldMergeMangaBlocks(b1.rect, b2.rect)) {
                        val mergedRect = Rect(
                            minOf(b1.rect.left, b2.rect.left),
                            minOf(b1.rect.top, b2.rect.top),
                            maxOf(b1.rect.right, b2.rect.right),
                            maxOf(b1.rect.bottom, b2.rect.bottom),
                        )
                        // In Japanese vertical manga, reading order is Right to Left
                        val mergedText = if (b1.rect.centerX() > b2.rect.centerX()) {
                            "${b1.text} ${b2.text}"
                        } else {
                            "${b2.text} ${b1.text}"
                        }
                        remaining[i] = RawBlock(mergedText, mergedRect)
                        remaining.removeAt(j)
                        changed = true
                        break
                    }
                    j++
                }
                if (changed) break
                i++
            }
        }
        return remaining
    }

    private fun shouldMergeMangaBlocks(r1: Rect, r2: Rect): Boolean {
        val overlapTop = maxOf(r1.top, r2.top)
        val overlapBottom = minOf(r1.bottom, r2.bottom)
        val overlapHeight = (overlapBottom - overlapTop).coerceAtLeast(0)
        val minHeight = minOf(r1.height(), r2.height())

        // Must share at least 45% of vertical height
        if (overlapHeight < minHeight * 0.45f) return false

        // Horizontal distance between columns
        val horizontalGap = if (r1.left > r2.right) {
            r1.left - r2.right
        } else if (r2.left > r1.right) {
            r2.left - r1.right
        } else {
            0 // Overlapping horizontally
        }

        val maxWidth = maxOf(r1.width(), r2.width())
        return horizontalGap < maxWidth * 2.2f && horizontalGap < 70
    }

    private suspend fun TextRecognizer.processImage(
        inputImage: InputImage,
    ): Text = suspendCancellableCoroutine { cont ->
        process(inputImage)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private data class RawBlock(
        val text: String,
        val rect: Rect,
    )
}
