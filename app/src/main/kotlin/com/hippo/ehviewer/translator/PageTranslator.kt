package com.hippo.ehviewer.translator

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

object PageTranslator {
    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    @Volatile
    private var currentKeyIndex = 0

    suspend fun translatePage(
        pageIndex: Int,
        bitmap: Bitmap,
        sourceLang: String = "ja",
        targetLang: String = "es",
        promptTemplate: String = TranslatorSettings.DEFAULT_PROMPT_TEMPLATE,
        engine: String = TranslatorSettings.ENGINE_GOOGLE,
        geminiApiKeys: List<String> = emptyList(),
        geminiModel: String = TranslatorSettings.DEFAULT_GEMINI_MODEL,
        grouping: BubbleGroupingConfig = BubbleGroupingConfig(),
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

        // Stitch the per-column OCR fragments of every balloon back together.
        // Without this a single vertical bubble is translated twice and drawn
        // as two overlapping boxes. Keep the raw blocks if analysis fails so a
        // detection bug can never break translation completely.
        val mergedBlocks = runCatching {
            BubbleDetector.group(rawBlocks, bitmap, grouping)
        }.getOrElse { rawBlocks }

        var bubbles: List<TranslatedBubble> = emptyList()
        var engineUsed = "Google"

        val transTime = measureTimeMillis {
            val texts = mergedBlocks.map { it.text }
            if (engine == TranslatorSettings.ENGINE_GEMINI && geminiApiKeys.isNotEmpty()) {
                engineUsed = "Gemini (${geminiModel.removePrefix("gemini-")})"
                try {
                    val translatedTexts = translateWithGemini(
                        texts = texts,
                        sourceLang = sourceLang,
                        targetLang = targetLang,
                        promptTemplate = promptTemplate,
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
                    engineUsed = "Google (Fallback: ${e.message?.take(20)})"
                    bubbles = translateWithGoogleBatch(mergedBlocks, sourceLang, targetLang)
                }
            } else {
                bubbles = translateWithGoogleBatch(mergedBlocks, sourceLang, targetLang)
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
        sourceLang: String,
        targetLang: String,
    ): List<TranslatedBubble> = coroutineScope {
        blocks.mapIndexed { idx, block ->
            async(Dispatchers.IO) {
                val translated = translateTextGoogle(block.text, sourceLang, targetLang)
                TranslatedBubble(
                    id = idx,
                    originalText = block.text,
                    translatedText = translated,
                    boundingBox = block.rect,
                )
            }
        }.awaitAll()
    }

    private suspend fun translateTextGoogle(
        text: String,
        sourceLang: String,
        targetLang: String,
    ): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext text

        // Primary Google endpoint with explicit source and target languages (never auto)
        val primaryResult = runCatching {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val urlString = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=$encoded"
            val url = URI.create(urlString).toURL()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                connectTimeout = 7000
                readTimeout = 7000
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
            cleanMangaText(sb.toString())
        }.getOrNull()

        if (primaryResult != null && primaryResult.isNotBlank()) {
            if (sourceLang != "ja" || !containsJapanese(primaryResult)) {
                return@withContext primaryResult
            }
        }

        // Secondary Google endpoint (dict-chrome-ex) fallback with explicit languages
        val secondaryResult = runCatching {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val urlString = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=$sourceLang&tl=$targetLang&q=$encoded"
            val url = URI.create(urlString).toURL()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
                connectTimeout = 7000
                readTimeout = 7000
            }
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(response)
            cleanMangaText(array.optString(0))
        }.getOrNull()

        if (secondaryResult != null && secondaryResult.isNotBlank()) {
            if (sourceLang != "ja" || !containsJapanese(secondaryResult)) {
                return@withContext secondaryResult
            }
        }

        cleanMangaText(primaryResult ?: secondaryResult ?: text)
    }

    private fun cleanMangaText(text: String): String = text
        .replace(Regex("[\\r\\n]+"), " ")
        .replace(Regex("([¿¡])\\s+"), "$1")
        .replace(Regex("\\s+([!?,.:;])"), "$1")
        .replace(Regex("\\s+"), " ")
        .trim()

    private suspend fun translateWithGemini(
        texts: List<String>,
        sourceLang: String,
        targetLang: String,
        promptTemplate: String,
        apiKeys: List<String>,
        model: String,
    ): List<String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        if (apiKeys.isEmpty()) error("No hay claves de API disponibles")

        val formattedInstruction = TranslatorSettings.formatGeminiPrompt(
            template = promptTemplate,
            sourceLang = sourceLang,
            targetLang = targetLang,
        )

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
            $formattedInstruction
            Return ONLY a valid JSON array of objects with "id" and "translated".
            Example format:
            [{"id": 0, "translated": "text"}]

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

                    val parsedArray = extractJsonArray(rawJson)
                    val results = arrayOfNulls<String>(texts.size)

                    for (i in 0 until parsedArray.length()) {
                        val item = parsedArray.get(i)
                        var transText = ""
                        var targetId = i

                        if (item is JSONObject) {
                            targetId = item.optInt("id", -1).takeIf { it != -1 }
                                ?: item.optString("id").toIntOrNull()
                                ?: i
                            transText = item.optString("translated").takeIf { it.isNotBlank() }
                                ?: item.optString("translation").takeIf { it.isNotBlank() }
                                ?: item.optString("text").takeIf { it.isNotBlank() }
                                ?: item.optString(targetLang).takeIf { it.isNotBlank() }
                                ?: ""
                        } else if (item is String) {
                            transText = item
                        }

                        if (targetId in results.indices && transText.isNotBlank()) {
                            results[targetId] = cleanMangaText(transText)
                        }
                    }

                    // Fallback to Google Translate for any missing or untranslated items
                    val finalTexts = mutableListOf<String>()
                    for (idx in texts.indices) {
                        val gText = results[idx]
                        if (gText != null && gText.isNotBlank() && (sourceLang != "ja" || !containsJapanese(gText))) {
                            finalTexts.add(gText)
                        } else {
                            val googleFallback = translateTextGoogle(texts[idx], sourceLang, targetLang)
                            finalTexts.add(googleFallback)
                        }
                    }

                    return@withContext finalTexts
                } else {
                    val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    lastError = IllegalStateException("Gemini API ($responseCode): $errorText")
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: IllegalStateException("Todas las claves de API fallaron")
    }

    private fun extractJsonArray(raw: String): JSONArray {
        var text = raw.trim()
        if (text.startsWith("```json", ignoreCase = true)) {
            text = text.substringAfter("```json", text)
        } else if (text.startsWith("```")) {
            text = text.substringAfter("```", text)
        }
        if (text.endsWith("```")) {
            text = text.substringBeforeLast("```", text)
        }
        text = text.trim()

        val startArr = text.indexOf('[')
        val endArr = text.lastIndexOf(']')
        if (startArr != -1 && endArr != -1 && endArr > startArr) {
            return JSONArray(text.substring(startArr, endArr + 1))
        }

        val startObj = text.indexOf('{')
        val endObj = text.lastIndexOf('}')
        if (startObj != -1 && endObj != -1 && endObj > startObj) {
            val rootObj = JSONObject(text.substring(startObj, endObj + 1))
            val keys = rootObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = rootObj.optJSONArray(key)
                if (arr != null) return arr
            }
        }

        return JSONArray(text)
    }

    private fun containsJapanese(str: String): Boolean = str.any { ch ->
        ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF' || ch in '\u4E00'..'\u9FAF'
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

    private suspend fun TextRecognizer.processImage(
        inputImage: InputImage,
    ): Text = suspendCancellableCoroutine { cont ->
        process(inputImage)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
