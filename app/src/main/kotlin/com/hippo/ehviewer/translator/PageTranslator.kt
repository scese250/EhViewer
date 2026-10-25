package com.hippo.ehviewer.translator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A translation failure that keeps the untouched API reply, so the reader can
 * show exactly what the server or the model answered instead of a fixed text.
 */
class TranslationFailure(
    message: String,
    val rawResponse: String,
) : Exception(message)

object PageTranslator {
    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    @Volatile
    private var currentKeyIndex = 0

    /** Reported before each expensive step so the UI can show what is running. */
    enum class Phase { OCR, VISION, TRANSLATE }

    /** Largest side sent to the model: enough to read balloons, small enough to stay fast. */
    private const val VISION_MAX_EDGE = 1600
    private const val VISION_JPEG_QUALITY = 88
    private const val GEMINI_READ_TIMEOUT_MS = 15_000
    private const val GEMINI_VISION_READ_TIMEOUT_MS = 60_000
    private const val MAX_EXTRA_BUBBLES = 4

    /** A long sentence marked as SFX is a model slip: draw it as a normal balloon. */
    private const val MAX_SFX_LENGTH = 24
    private const val GRID_CELLS = 20
    private val markerColor = 0xFFFF0033.toInt()

    suspend fun translatePage(
        pageIndex: Int,
        bitmap: Bitmap,
        sourceLang: String = "Japanese",
        targetLang: String = "English",
        systemPrompt: String = TranslatorSettings.DEFAULT_SYSTEM_PROMPT,
        userPrompt: String = "",
        geminiApiKeys: List<String> = emptyList(),
        geminiModel: String = TranslatorSettings.DEFAULT_GEMINI_MODEL,
        thinkingLevel: String = TranslatorSettings.THINKING_MINIMAL,
        onPhase: (Phase) -> Unit = {},
    ): PageTranslation = withContext(Dispatchers.Default) {
        // The model is the only engine left: without a key there is nothing to
        // call, so fail loudly instead of silently using another translator.
        if (geminiApiKeys.isEmpty()) {
            error("No Gemini API key: add one in the translation settings.")
        }

        suspend fun recognize(): Pair<List<RawBlock>, Long> {
            var visionText: Text? = null
            val ms = measureTimeMillis {
                visionText = recognizer.processImage(InputImage.fromBitmap(bitmap, 0))
            }
            val blocks = visionText?.textBlocks.orEmpty()
                .filter { block ->
                    block.text.isNotBlank() &&
                        (block.boundingBox?.width() ?: 0) > 8 &&
                        (block.boundingBox?.height() ?: 0) > 8
                }
                .map { block ->
                    RawBlock(
                        text = block.text.replace("\n", " ").trim(),
                        rect = block.boundingBox ?: Rect(),
                    )
                }
            return blocks to ms
        }

        var ocrTime = 0L
        var bubbles: List<TranslatedBubble> = emptyList()
        var engineUsed = "Gemini"
        var mergedBoxes = 0
        var discardedBoxes = 0

        val transTime = measureTimeMillis {
            var translated = false
            var visionFailure: Exception? = null

            // The model reads the page and measures every balloon itself; the
            // on-device OCR only exists as a rescue path now.
            onPhase(Phase.VISION)
            try {
                val result = translateWithGeminiVision(
                    bitmap = bitmap,
                    blocks = emptyList(),
                    noHints = true,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    apiKeys = geminiApiKeys,
                    model = geminiModel,
                    thinkingLevel = thinkingLevel,
                )
                bubbles = result.bubbles
                mergedBoxes = result.mergedBoxes
                discardedBoxes = result.discardedBoxes
                engineUsed = "Gemini (${geminiModel.removePrefix("gemini-")})"
                translated = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslationFailure) {
                // The API or the model already answered: surface that reply
                // instead of spending seconds on the OCR rescue.
                throw e
            } catch (e: Exception) {
                visionFailure = e
            }

            if (!translated) {
                // Last resort: run ML Kit and send the recognised text alone.
                onPhase(Phase.OCR)
                val (blocks, ms) = recognize()
                ocrTime += ms
                // Stitch the per-column OCR fragments of every balloon back
                // together so a vertical bubble is not translated twice.
                val mergedBlocks = runCatching {
                    // The detection controls are gone: the rescue path uses the
                    // tuned defaults of the detector.
                    BubbleDetector.group(blocks, bitmap, BubbleGroupingConfig())
                }.getOrElse { blocks }
                onPhase(Phase.TRANSLATE)
                try {
                    bubbles = translateWithGeminiText(
                        blocks = mergedBlocks,
                        sourceLang = sourceLang,
                        targetLang = targetLang,
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        apiKeys = geminiApiKeys,
                        model = geminiModel,
                        thinkingLevel = thinkingLevel,
                    )
                    engineUsed = "Gemini text (vision failed: ${visionFailure?.message?.take(40)})"
                    translated = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Prefer the newest reply: if the text call failed too, its
                    // raw body explains why (quota, safety, bad model...).
                    val failure = (e as? TranslationFailure)
                        ?: (visionFailure as? TranslationFailure)
                    if (failure != null) throw failure
                    throw TranslationFailure(
                        message = "Gemini could not translate this page",
                        rawResponse = (visionFailure ?: e).message.orEmpty(),
                    )
                }
            }
        }

        PageTranslation(
            pageIndex = pageIndex,
            bubbles = bubbles,
            ocrDurationMs = ocrTime,
            translateDurationMs = transTime,
            totalDurationMs = ocrTime + transTime,
            engineName = engineUsed,
            mergedBoxes = mergedBoxes,
            discardedBoxes = discardedBoxes,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
        )
    }

    private suspend fun translateWithGeminiText(
        blocks: List<RawBlock>,
        sourceLang: String,
        targetLang: String,
        systemPrompt: String,
        userPrompt: String = "",
        apiKeys: List<String>,
        model: String,
        thinkingLevel: String,
    ): List<TranslatedBubble> {
        val translated = translateWithGemini(
            texts = blocks.map { it.text },
            sourceLang = sourceLang,
            targetLang = targetLang,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            apiKeys = apiKeys,
            model = model,
            thinkingLevel = thinkingLevel,
        )
        val bubbles = mutableListOf<TranslatedBubble>()
        blocks.forEachIndexed { idx, block ->
            // Null means the model rejected the line as unreadable or garbled.
            val text = translated.getOrNull(idx) ?: return@forEachIndexed
            bubbles += TranslatedBubble(
                id = bubbles.size,
                originalText = block.text,
                translatedText = text,
                boundingBox = block.rect,
            )
        }
        return bubbles
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
        systemPrompt: String,
        userPrompt: String = "",
        apiKeys: List<String>,
        model: String,
        thinkingLevel: String,
    ): List<String?> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        if (apiKeys.isEmpty()) error("No hay claves de API disponibles")

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
            These are the dialogue lines of a manga page, translated from
            ${TranslatorSettings.getLanguageName(sourceLang)} into
            ${TranslatorSettings.getLanguageName(targetLang)}.
            The OCR behind this text is unreliable: lines can be truncated,
            misspelled or contain fragments of a neighbouring balloon. Read each
            line as a whole and translate what it is meant to say.

            If a line is not readable dialogue (illegible garbage, logo, watermark,
            page number, sound effect), do not translate it: answer exactly
            "REJECT" for that id.

            Return ONLY a valid JSON array of objects with "id" and "translated".
            Example format:
            [{"id": 0, "translated": "text"}, {"id": 1, "translated": "REJECT"}]

            Input dialogue:
            $inputItems
        """.trimIndent() + userPromptSuffix(userPrompt, sourceLang, targetLang)

        val requestJson = JSONObject().apply {
            val parts = JSONArray().apply {
                put(JSONObject().apply { put("text", prompt) })
            }
            val contents = JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", parts)
                    },
                )
            }
            put("contents", contents)
            // The translator's rules travel as the system prompt; the dialogue
            // and, in vision mode, the page image are the user turn.
            val effectiveSystemPrompt = systemPrompt.ifBlank { TranslatorSettings.DEFAULT_SYSTEM_PROMPT }
            put("systemInstruction", systemInstruction(effectiveSystemPrompt, sourceLang, targetLang))
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.2)
                    put("responseMimeType", "application/json")
                    putThinkingConfig(thinkingLevel)
                },
            )
        }

        val root = postGemini(
            apiKeys,
            model,
            requestJson,
            readTimeoutFor(thinkingLevel, GEMINI_READ_TIMEOUT_MS),
        )
        val parsedArray = extractJsonArray(candidateText(root))
        val results = arrayOfNulls<String>(texts.size)
        val rejected = BooleanArray(texts.size)

        for (i in 0 until parsedArray.length()) {
            val item = parsedArray.opt(i)
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

            if (targetId !in results.indices || transText.isBlank()) continue
            if (isReject(transText)) {
                rejected[targetId] = true
                continue
            }
            val clean = cleanMangaText(transText)
            if (clean.isNotBlank() && (!TranslatorSettings.isJapanese(sourceLang) || !containsJapanese(clean))) {
                results[targetId] = clean
            }
        }

        // Rejected lines are dropped and lines the model skipped stay empty:
        // with a single engine there is no second translator to recover them.
        texts.indices.map { idx ->
            if (rejected[idx]) null else results[idx]
        }
    }

    /**
     * The model is told to answer "REJECT" for a balloon it cannot read. Such
     * balloons are dropped instead of being drawn with the OCR garbage.
     */
    private fun isReject(text: String): Boolean {
        val normalized = text.trim().trim('"', '\'', '[', ']', '.', '!', ':', ' ')
        if (normalized.equals("REJECT", ignoreCase = true)) return true
        // Tolerate "REJECT: reason" and similar qualifiers.
        return normalized.startsWith("REJECT", ignoreCase = true) &&
            normalized.length > 6 &&
            !normalized[6].isLetterOrDigit()
    }

    /** A reasoning model answers slower; the socket must wait for it. */
    private fun readTimeoutFor(thinkingLevel: String, baseMs: Int): Int = when (thinkingLevel) {
        TranslatorSettings.THINKING_HIGH -> maxOf(baseMs, 180_000)
        TranslatorSettings.THINKING_MEDIUM -> maxOf(baseMs, 120_000)
        TranslatorSettings.THINKING_LOW -> maxOf(baseMs, 90_000)
        else -> baseMs
    }

    /**
     * Requests the reasoning budget chosen in the settings. The REST API spells
     * the levels in upper case; older models answer 400 to an unknown field and
     * [postGemini] retries the same key without it.
     */
    private fun JSONObject.putThinkingConfig(level: String) {
        put(
            "thinkingConfig",
            JSONObject().apply { put("thinkingLevel", level.uppercase()) },
        )
    }

    private fun postGemini(
        apiKeys: List<String>,
        model: String,
        request: JSONObject,
        readTimeoutMs: Int,
    ): JSONObject {
        if (apiKeys.isEmpty()) error("No hay claves de API disponibles")

        var lastError: Exception? = null
        val totalKeys = apiKeys.size
        val generationConfig = request.optJSONObject("generationConfig")
        // Older models reject thinkingConfig as an unknown field. Retry the
        // same key once without it instead of failing the whole page.
        var retriedWithoutThinking = generationConfig?.has("thinkingConfig") != true

        var attempt = 0
        while (attempt < totalKeys) {
            val keyIndex = (currentKeyIndex + attempt) % totalKeys
            val urlString = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${apiKeys[keyIndex]}"
            val conn = (URI.create(urlString).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15_000
                readTimeout = readTimeoutMs
                doOutput = true
            }

            try {
                conn.outputStream.use { os ->
                    os.write(request.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    currentKeyIndex = keyIndex
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    return JSONObject(body)
                }

                val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                // Keep the body verbatim: the reader shows it as the reason.
                lastError = TranslationFailure(
                    message = "Gemini API ($responseCode)",
                    rawResponse = errorText.ifBlank { "HTTP $responseCode" },
                )
                if (responseCode == 400 && !retriedWithoutThinking &&
                    errorText.contains("thinking", ignoreCase = true)
                ) {
                    retriedWithoutThinking = true
                    generationConfig?.remove("thinkingConfig")
                    // Same key, same attempt, smaller request.
                    continue
                }
                // A malformed request or an unknown model fails the same way
                // with every key: don't burn the timeout once per key.
                if (responseCode in 400..499 && responseCode != 429) break
            } catch (e: Exception) {
                lastError = e
            } finally {
                conn.disconnect()
            }
            attempt++
        }

        throw lastError ?: TranslationFailure(
            message = "Gemini API request failed",
            rawResponse = "No response from the API",
        )
    }

    /** The user's extra instructions, formatted and trimmed; empty when unset. */
    private fun formatUserPrompt(
        userPrompt: String,
        sourceLang: String,
        targetLang: String,
    ): String = if (userPrompt.isBlank()) {
        ""
    } else {
        TranslatorSettings.formatGeminiPrompt(userPrompt, sourceLang, targetLang).trim()
    }

    private fun userPromptSuffix(
        userPrompt: String,
        sourceLang: String,
        targetLang: String,
    ): String {
        val extra = formatUserPrompt(userPrompt, sourceLang, targetLang)
        return if (extra.isEmpty()) "" else "\n\nAdditional reader instructions:\n$extra"
    }

    /** The translator's rules travel outside the conversation, as the system prompt. */
    private fun systemInstruction(
        systemPrompt: String,
        sourceLang: String,
        targetLang: String,
    ): JSONObject = JSONObject().apply {
        val text = TranslatorSettings.formatGeminiPrompt(systemPrompt, sourceLang, targetLang)
        put(
            "parts",
            JSONArray().apply {
                put(JSONObject().apply { put("text", text) })
            },
        )
    }

    private fun candidateText(root: JSONObject): String {
        val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        if (blockReason.isNotBlank()) {
            throw TranslationFailure("Gemini blocked the image", root.toString())
        }

        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw TranslationFailure("Gemini returned no answer", root.toString())
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            ?: throw TranslationFailure("Gemini returned an empty answer", root.toString())

        val builder = StringBuilder()
        for (i in 0 until parts.length()) {
            parts.optJSONObject(i)?.optString("text")?.let(builder::append)
        }

        val text = builder.toString().trim()
        if (text.isEmpty()) {
            // The raw reply carries the finishReason (SAFETY, RECITATION, ...).
            throw TranslationFailure("Gemini returned an empty answer", root.toString())
        }
        return text
    }

    private class VisionResult(
        val bubbles: List<TranslatedBubble>,
        val mergedBoxes: Int,
        val discardedBoxes: Int,
    )

    private class VisionEntry(
        val ids: List<Int>,
        val source: String?,
        val translated: String,
        val rect: Rect?,
        val isSfx: Boolean = false,
        val fromExtra: Boolean = false,
    )

    private data class EncodedVisionImage(
        val base64: String,
        val sentWidth: Int,
        val sentHeight: Int,
    )

    /**
     * Encodes the bitmap for the Vision API without drawing any grid overlay.
     * Used in "no OCR" mode so that Lite models don't confuse grid lines with artwork strokes.
     */
    private fun encodePageForVision(bitmap: Bitmap): EncodedVisionImage {
        val w = bitmap.width
        val h = bitmap.height
        val maxEdge = maxOf(w, h)
        val (nw, nh) = if (maxEdge > VISION_MAX_EDGE) {
            val ratio = VISION_MAX_EDGE.toFloat() / maxEdge.toFloat()
            Pair((w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1))
        } else {
            Pair(w, h)
        }
        val scaled = if (nw != w || nh != h) Bitmap.createScaledBitmap(bitmap, nw, nh, true) else bitmap
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, VISION_JPEG_QUALITY, out)
        if (scaled !== bitmap) scaled.recycle()
        return EncodedVisionImage(
            base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
            sentWidth = nw,
            sentHeight = nh,
        )
    }

    private data class RawBox(val y1: Float, val x1: Float, val y2: Float, val x2: Float)

    private fun JSONObject.extractRawBox(): RawBox? {
        val arr = optJSONArray("box") ?: optJSONArray("box_2d") ?: return null
        if (arr.length() < 4) return null
        val v0 = arr.optDouble(0, Double.NaN).toFloat()
        val v1 = arr.optDouble(1, Double.NaN).toFloat()
        val v2 = arr.optDouble(2, Double.NaN).toFloat()
        val v3 = arr.optDouble(3, Double.NaN).toFloat()
        if (v0.isNaN() || v1.isNaN() || v2.isNaN() || v3.isNaN()) return null
        return RawBox(y1 = v0, x1 = v1, y2 = v2, x2 = v3)
    }

    private fun detectCoordinateScale(
        rawBoxes: List<RawBox>,
        sentWidth: Int,
        sentHeight: Int,
    ): Pair<Float, Float> {
        if (rawBoxes.isEmpty()) return 1000f to 1000f
        var maxX = 0f
        var maxY = 0f
        for (b in rawBoxes) {
            maxX = maxOf(maxX, b.x1, b.x2)
            maxY = maxOf(maxY, b.y1, b.y2)
        }
        val maxGlobal = maxOf(maxX, maxY)
        return when {
            maxGlobal <= 1.05f -> 1f to 1f
            maxGlobal <= 100f && (rawBoxes.size > 1 || maxGlobal > 10f) -> 100f to 100f
            maxGlobal > 1050f -> sentWidth.coerceAtLeast(1).toFloat() to sentHeight.coerceAtLeast(1).toFloat()
            else -> 1000f to 1000f
        }
    }

    private fun RawBox.toPixelRect(
        scaleX: Float,
        scaleY: Float,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): Rect? {
        val normX1 = (minOf(x1, x2) / scaleX).coerceIn(0f, 1f)
        val normY1 = (minOf(y1, y2) / scaleY).coerceIn(0f, 1f)
        val normX2 = (maxOf(x1, x2) / scaleX).coerceIn(0f, 1f)
        val normY2 = (maxOf(y1, y2) / scaleY).coerceIn(0f, 1f)
        val left = (normX1 * bitmapWidth).toInt().coerceIn(0, bitmapWidth)
        val top = (normY1 * bitmapHeight).toInt().coerceIn(0, bitmapHeight)
        val right = (normX2 * bitmapWidth).toInt().coerceIn(0, bitmapWidth)
        val bottom = (normY2 * bitmapHeight).toInt().coerceIn(0, bitmapHeight)
        if (right <= left || bottom <= top) return null
        return Rect(left, top, right, bottom)
    }

    /**
     * Anchors the AI-estimated rectangle (no-OCR mode) to the real speech bubble
     * detected in the bitmap by [BubbleDetector.findEnclosingBubble].
     *
     * Returns Pair(anchoredBox, exactBox).
     * exactBox=true means the returned box is pixel-precise (SFX); false means the
     * overlay should centre the text and expand the bubble automatically.
     */
    private fun anchorVisionGuessWithoutOcr(
        bitmap: Bitmap,
        guess: Rect,
        isSfx: Boolean,
    ): Pair<Rect, Boolean> {
        // In no-OCR mode the model now reports the full balloon interior.
        // Use the coordinates exactly as-is (exactBox=true) so the overlay draws
        // directly over the balloon without horizontal expansion or collision shifting.
        return guess to true
    }

    /**
     * Sends the whole page with the OCR boxes drawn and numbered, and lets the
     * vision model do the actual reading: it recovers text the OCR truncated,
     * joins boxes that belong to the same balloon and drops boxes that are
     * duplicates or landed on artwork (answering "REJECT" for them).
     */
    private suspend fun translateWithGeminiVision(
        bitmap: Bitmap,
        blocks: List<RawBlock>,
        noHints: Boolean = false,
        sourceLang: String,
        targetLang: String,
        systemPrompt: String,
        userPrompt: String = "",
        apiKeys: List<String>,
        model: String,
        thinkingLevel: String,
    ): VisionResult = withContext(Dispatchers.IO) {
        // In no-OCR mode (noHints=true) we annotate with an empty block list:
        // the coordinate grid is drawn (so the model has a visual ruler to report
        // accurate coordinates) but NO red OCR boxes are drawn (there are no OCR
        // hints to show). Sending a completely clean image caused the model to
        // report wildly inaccurate coordinates because the prompt references a
        // grid that was not visible.
        val annotated = try {
            annotateForVision(bitmap, if (noHints) emptyList() else blocks)
        } catch (e: OutOfMemoryError) {
            error("Not enough memory to prepare the image")
        }
        val scale = min(1f, VISION_MAX_EDGE.toFloat() / max(bitmap.width, bitmap.height))
        val encodedImage = EncodedVisionImage(
            base64 = "",
            sentWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            sentHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1),
        )
        val imageBase64 = try {
            ByteArrayOutputStream().use { stream ->
                if (!annotated.compress(Bitmap.CompressFormat.JPEG, VISION_JPEG_QUALITY, stream)) {
                    error("No se pudo comprimir la imagen")
                }
                Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            }
        } finally {
            annotated.recycle()
        }

        val prompt = buildVisionPrompt(targetLang, blocks.size, noHints)

        val requestJson = JSONObject().apply {
            val parts = JSONArray().apply {
                // The page is the user turn: image first, then the task.
                put(
                    JSONObject().apply {
                        put(
                            "inline_data",
                            JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", imageBase64)
                            },
                        )
                    },
                )
                put(JSONObject().apply { put("text", prompt) })
                formatUserPrompt(userPrompt, sourceLang, targetLang).takeIf { it.isNotEmpty() }?.let { extra ->
                    put(JSONObject().apply { put("text", extra) })
                }
            }
            put(
                "contents",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("parts", parts)
                        },
                    )
                },
            )
            val effectiveSystemPrompt = systemPrompt.ifBlank { TranslatorSettings.DEFAULT_SYSTEM_PROMPT }
            put("systemInstruction", systemInstruction(effectiveSystemPrompt, sourceLang, targetLang))
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.1)
                    put("responseMimeType", "application/json")
                    // The reasoning budget is charged to this same limit, so a
                    // high level needs room or the JSON answer is truncated.
                    put(
                        "maxOutputTokens",
                        when (thinkingLevel) {
                            TranslatorSettings.THINKING_HIGH -> 32768
                            TranslatorSettings.THINKING_MEDIUM -> 16384
                            TranslatorSettings.THINKING_LOW -> 8192
                            else -> 4096
                        },
                    )
                    putThinkingConfig(thinkingLevel)
                },
            )
        }

        val root = postGemini(
            apiKeys,
            model,
            requestJson,
            readTimeoutFor(thinkingLevel, GEMINI_VISION_READ_TIMEOUT_MS),
        )
        parseVisionResult(
            raw = candidateText(root),
            blocks = blocks,
            width = bitmap.width,
            height = bitmap.height,
            maxExtras = if (noHints) MAX_EXTRA_BUBBLES * 2 else MAX_EXTRA_BUBBLES,
            bitmap = if (noHints) bitmap else null,
            sentWidth = encodedImage.sentWidth,
            sentHeight = encodedImage.sentHeight,
        )
    }

    private fun buildVisionPrompt(
        targetLang: String,
        boxCount: Int,
        noHints: Boolean = false,
    ): String = buildString {
        if (noHints) {
            val langName = TranslatorSettings.getLanguageName(targetLang)
            appendLine("You are the OCR and direct text overlay lettering engine for this manga page: the attached image is the complete page.")
            appendLine("Your job is to read every block of Japanese text, translate it to $langName, and provide the exact bounding box of the Japanese text characters so the translated $langName text is rendered directly on top of the original Japanese text.")
            appendLine()
            appendLine("Coordinates:")
            appendLine("- Use normalised coordinates 0-1000 for both axes (0,0 = top-left of the image, 1000,1000 = bottom-right).")
            appendLine("- The \"box\" field is [ymin, xmin, ymax, xmax].")
            appendLine()
            appendLine("Rules:")
            appendLine("1. Return one entry per block of dialogue, monologue, or caption, in Japanese manga reading order (right to left, top to bottom).")
            appendLine("2. \"ids\" must always be empty ([]).")
            appendLine("3. \"box\": [ymin, xmin, ymax, xmax] must tightly enclose the JAPANESE CHARACTERS of each dialogue block:")
            appendLine("   - Enclose all vertical columns of that sentence together (from the top of the highest character to the bottom of the lowest, and from the leftmost column to the rightmost column).")
            appendLine("   - The app stamps the translated $langName text directly ON TOP of the original Japanese characters with a thick white outline.")
            appendLine("   - Do NOT report the speech balloon interior or padding. Do NOT place the box over empty space, and NEVER place the box over a character's face or body unless the Japanese text is literally drawn over them.")
            appendLine("   - The box does not auto-grow or expand: it is the exact placement target where the translated text will sit directly over the original Japanese text.")
            appendLine("4. If a sentence spans across multiple adjacent vertical columns in the same bubble, join them into a single entry covering all those columns.")
            appendLine("5. Onomatopoeia painted on artwork must be returned in \"extra\" with \"kind\": \"sfx\" and \"box\" tightly covering the sound effect characters.")
            appendLine("6. Ignore non-manga text: watermarks, scanlation credits, page numbers, store logos, and URLs.")
            appendLine("7. Do not add notes, romanisation or explanations.")
            appendLine()
            appendLine("Reply with raw JSON only, without markdown fences or commentary:")
            append("{\"bubbles\":[{\"ids\":[],\"box\":[ymin,xmin,ymax,xmax],\"source\":\"japanese text as printed\",")
            append("\"text\":\"")
            append(langName)
            append(" translation\",\"kind\":\"dialogue\"}],")
            append("\"extra\":[{\"box\":[ymin,xmin,ymax,xmax],\"source\":\"...\",\"text\":\"...\",\"kind\":\"sfx\"}]}")
        } else {
            appendLine("You are ALSO the OCR engine and the bubble layout engine for this manga page: the attached")
            appendLine("image is the complete page.")
            appendLine("A weak OCR has drawn numbered red boxes over the places where it thinks text may be.")
            appendLine("Those boxes are only hints; their text is not given to you and both their position and their")
            appendLine("size may be wrong. Look at the page yourself, read the text that is really inside each place,")
            appendLine("translate it, and measure the area the translated text must cover.")
            appendLine("A blue grid is drawn over the page: 20 x 20 cells, each cell 50 units wide, with the")
            appendLine("normalised coordinate (0-1000) printed along all four edges. Use it as a ruler.")
            if (boxCount == 0) {
                appendLine("This page has no numbered boxes: recover every speech balloon you can see with \"extra\".")
            }
            appendLine()
            appendLine("Rules:")
            appendLine("1. Return one entry per speech balloon of the finished page, in reading order")
            appendLine("   (Japanese manga: right to left, top to bottom).")
            appendLine("2. \"ids\" lists every numbered box that belongs to that balloon. If one balloon was split into")
            appendLine("   two boxes (for example 1 and 2), return a SINGLE entry with \"ids\": [1, 2] and the whole")
            appendLine("   text of the balloon. Never return two entries for the same balloon.")
            appendLine("3. If a box has no readable dialogue (it landed on artwork, on a sound effect, or it is a")
            appendLine("   duplicate fragment of another box), do not translate it: answer exactly \"REJECT\" in its")
            appendLine("   text. Do not invent text for it.")
            appendLine("4. If a sentence continues from one box into another box of the same balloon, join both ids in one entry.")
            appendLine("5. Every entry must also carry \"box\": [ymin, xmin, ymax, xmax], the normalised rectangle")
            appendLine("   (0-1000) of the TEXT ITSELF, not of the balloon. Follow the lines of dialogue and leave out")
            appendLine("   the balloon padding, the balloon tail, the character's face and the neighbouring balloon.")
            appendLine("   The translation is centred inside that rectangle and, when the translated text is longer")
            appendLine("   than the original, it grows upwards and downwards around the centre on its own, so keep")
            appendLine("   the rectangle centred on the original text and cover only the text. The lettering size is")
            appendLine("   fixed by the app, so the rectangle decides the area to cover, never how big the text is.")
            appendLine("6. A balloon that only holds a sound effect, or that has no text at all, gets no entry:")
            appendLine("   answer exactly \"REJECT\" for it.")
            appendLine("7. Ignore text outside balloons: credits, watermarks, page numbers, store logos, meta")
            appendLine("   commentary, shop signs, posters, book spines and text painted on the artwork.")
            appendLine("8. Onomatopoeia painted on the artwork is not a balloon: return it as an \"extra\" entry with")
            appendLine("   \"kind\": \"sfx\" and a short sound word (\"¡BAM!\", \"¡DOON!\"). It is lettered on a")
            appendLine("   single horizontal line, so never wrap it in a sentence.")
            appendLine("9. Use the panel around each balloon to decide who is speaking: first or third person, and the")
            appendLine("   gender of the speaker. Keep the same person and gender for every line of the conversation.")
            appendLine("10. Do not add notes, romanisation or explanations.")
            appendLine()
            appendLine("Reply with JSON only, without prose or code fences:")
            append("{\"bubbles\":[{\"ids\":[1],\"box\":[ymin,xmin,ymax,xmax],\"source\":\"text as printed\",")
            append("\"text\":\"")
            append(TranslatorSettings.getLanguageName(targetLang))
            append(" translation\",\"kind\":\"dialogue\"}],")
            append("\"extra\":[{\"box\":[ymin,xmin,ymax,xmax],\"source\":\"...\",\"text\":\"...\",\"kind\":\"sfx\"}]}")
            appendLine()
            appendLine("\"source\" is the text exactly as printed on the page. \"kind\" is \"dialogue\" for speech and")
            appendLine("\"sfx\" for onomatopoeia. \"extra\" is for balloons with no numbered box and for sound")
            appendLine("effects painted on the artwork. Use at most $MAX_EXTRA_BUBBLES extra entries.")
        }
    }

    private fun annotateForVision(bitmap: Bitmap, blocks: List<RawBlock>): Bitmap {
        val scale = min(1f, VISION_MAX_EDGE.toFloat() / max(bitmap.width, bitmap.height))
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)

        // Never draw on the reader's bitmap: it is still pinned for display.
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(
            bitmap,
            Matrix().apply {
                setScale(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())
            },
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
        )
        drawVisionGrid(canvas, width, height)

        val stroke = max(2f, width / 600f)
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            // Thin halo: the boxes hug the glyphs, so a fat outline would eat
            // the strokes the model has to read.
            strokeWidth = stroke * 1.8f
            color = Color.WHITE
        }
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = markerColor
        }
        val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = markerColor }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = max(14f, width / 55f)
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        blocks.forEachIndexed { index, block ->
            val rect = Rect(
                (block.rect.left * scale).roundToInt(),
                (block.rect.top * scale).roundToInt(),
                (block.rect.right * scale).roundToInt(),
                (block.rect.bottom * scale).roundToInt(),
            )
            canvas.drawRect(rect, halo)
            canvas.drawRect(rect, frame)

            val text = (index + 1).toString()
            val boxWidth = max(label.measureText(text) * 1.6f, label.textSize * 1.5f)
            val boxHeight = label.textSize * 1.7f
            val left = rect.left.toFloat().coerceIn(0f, (width - boxWidth).coerceAtLeast(0f))
            // Prefer the badge just above the box so the number never covers
            // the dialogue the model has to read.
            val top = (rect.top - boxHeight).coerceAtLeast(0f)
            val badgeRect = RectF(left, top, left + boxWidth, top + boxHeight)
            canvas.drawRect(badgeRect, badge)
            canvas.drawText(
                text,
                badgeRect.centerX(),
                badgeRect.centerY() - (label.ascent() + label.descent()) / 2f,
                label,
            )
        }
        return output
    }

    /**
     * Ruler grid (0-1000 on both axes) so the model can report the balloon
     * rectangles itself: the OCR boxes are as imprecise as their text.
     */
    private fun drawVisionGrid(canvas: Canvas, width: Int, height: Int) {
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, width / 900f)
            color = 0x402962FF.toInt()
        }
        for (step in 1 until GRID_CELLS) {
            val x = width * step.toFloat() / GRID_CELLS
            val y = height * step.toFloat() / GRID_CELLS
            canvas.drawLine(x, 0f, x, height.toFloat(), line)
            canvas.drawLine(0f, y, width.toFloat(), y, line)
        }

        val backing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9FFFFFF.toInt() }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF101010.toInt()
            textSize = max(10f, width / 80f)
            isFakeBoldText = true
        }
        val pad = label.textSize * 0.25f
        val boxHeight = label.textSize + pad * 2
        // One label per grid line (every 100 units) on all four edges: four
        // times the reference points of the old 200-step, two-edge ruler, with
        // no extra line drawn over the artwork. Corners are shared by two
        // rulers, so the duplicate label is skipped instead of stacking.
        for (value in 0..1000 step 100) {
            val text = value.toString()
            val boxWidth = label.measureText(text) + pad * 2
            val x = (width * value / 1000f - boxWidth / 2f).coerceIn(0f, (width - boxWidth).coerceAtLeast(0f))
            val y = (height * value / 1000f - boxHeight / 2f).coerceIn(0f, (height - boxHeight).coerceAtLeast(0f))
            // Top edge (x ruler) and left edge (y ruler, first corner only).
            drawGridLabel(canvas, backing, label, text, x, 0f, boxWidth, boxHeight)
            if (value > 0) drawGridLabel(canvas, backing, label, text, 0f, y, boxWidth, boxHeight)
            // Bottom edge, minus the corner the left ruler already labels.
            if (value > 0) {
                drawGridLabel(canvas, backing, label, text, x, height - boxHeight, boxWidth, boxHeight)
            }
            // Right edge, minus both corners shared with the x rulers.
            if (value in 100..900) {
                drawGridLabel(canvas, backing, label, text, width - boxWidth, y, boxWidth, boxHeight)
            }
        }
    }

    private fun drawGridLabel(
        canvas: Canvas,
        backing: Paint,
        labelPaint: Paint,
        text: String,
        left: Float,
        top: Float,
        boxWidth: Float,
        boxHeight: Float,
    ) {
        canvas.drawRect(left, top, left + boxWidth, top + boxHeight, backing)
        val baseline = top + boxHeight / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f
        canvas.drawText(text, left + (boxWidth - labelPaint.measureText(text)) / 2f, baseline, labelPaint)
    }

    private fun parseVisionResult(
        raw: String,
        blocks: List<RawBlock>,
        width: Int,
        height: Int,
        maxExtras: Int = MAX_EXTRA_BUBBLES,
        bitmap: Bitmap? = null, // needed for anchorVisionGuessWithoutOcr
        sentWidth: Int = width,
        sentHeight: Int = height,
    ): VisionResult {
        val root = runCatching { extractJsonObject(raw) }.getOrElse {
            JSONObject().put("bubbles", extractJsonArray(raw))
        }
        val entries = mutableListOf<VisionEntry>()

        // Pre-extract all raw boxes to auto-detect coordinate scale for this page.
        val rawBoxesByIndex = HashMap<Int, RawBox>()
        run {
            val arr = findVisionArray(root) ?: JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                obj.extractRawBox()?.let { rawBoxesByIndex[i] = it }
            }
            val extrasArr = root.optJSONArray("extra") ?: root.optJSONArray("extras") ?: JSONArray()
            for (i in 0 until extrasArr.length()) {
                val obj = extrasArr.optJSONObject(i) ?: continue
                obj.extractRawBox()?.let { rawBoxesByIndex[-(i + 1)] = it }
            }
        }
        val (coordScaleX, coordScaleY) = detectCoordinateScale(
            rawBoxes = rawBoxesByIndex.values.toList(),
            sentWidth = sentWidth,
            sentHeight = sentHeight,
        )

        val extras = root.optJSONArray("extra") ?: root.optJSONArray("extras") ?: JSONArray()
        val bubbleArray = findVisionArray(root)
            ?: if (extras.length() == 0) {
                throw TranslationFailure("The model returned no bubbles", raw)
            } else {
                JSONArray()
            }
        for (i in 0 until bubbleArray.length()) {
            val item = bubbleArray.optJSONObject(i) ?: continue
            val ids = readIds(item)
            val translated = readText(item)
            if (translated.isBlank()) continue
            if (isReject(translated)) {
                // Explicit rejection: this box is never drawn.
                entries += VisionEntry(ids, null, "", null)
                continue
            }
            entries += VisionEntry(
                ids = ids,
                source = readSource(item),
                translated = translated,
                rect = rawBoxesByIndex[i]?.toPixelRect(coordScaleX, coordScaleY, width, height)
                    ?: readNormalizedRect(item, width, height),
                isSfx = readSfx(item) && translated.trim().length <= MAX_SFX_LENGTH,
            )
        }

        var extraCount = 0
        for (i in 0 until extras.length()) {
            if (extraCount >= maxExtras) break
            val item = extras.optJSONObject(i) ?: continue
            val translated = readText(item)
            if (translated.isBlank() || isReject(translated)) continue
            val rect = readNormalizedRect(item, width, height) ?: continue
            entries += VisionEntry(
                ids = emptyList(),
                source = readSource(item),
                translated = translated,
                rect = rect,
                isSfx = readSfx(item) && translated.trim().length <= MAX_SFX_LENGTH,
                fromExtra = true,
            )
            extraCount++
        }

        val used = mutableSetOf<Int>()
        val accepted = mutableListOf<Rect>()
        val bubbles = mutableListOf<TranslatedBubble>()
        var mergedBoxes = 0

        for (entry in entries) {
            // Rejected and empty entries are deliberately not drawn, so their
            // ids stay unused and are reported as discarded boxes.
            if (entry.translated.isBlank()) continue
            // A box already claimed by another balloon must not be drawn twice.
            if (entry.ids.any { it in used }) continue

            val sources = entry.ids.mapNotNull { id -> blocks.getOrNull(id - 1) }
            val union = sources
                .map { it.rect }
                .filter { it.width() > 0 && it.height() > 0 }
                .reduceOrNull { acc, rect ->
                    Rect(acc).apply {
                        left = min(left, rect.left)
                        top = min(top, rect.top)
                        right = max(right, rect.right)
                        bottom = max(bottom, rect.bottom)
                    }
                }
            val guess = entry.rect?.takeIf { it.isPlausibleBubble(width, height) }
            var fromModel = false
            val rect = when {
                // The model's own rectangle wins: it follows the balloon outline,
                // not the OCR text. Accept it only when it sits near the hint
                // boxes, so a hallucinated rectangle cannot land on the artwork.
                guess != null && (union == null || Rect.intersects(guess, union.expanded(width, height))) -> {
                    fromModel = true
                    guess
                }
                union != null -> union
                guess != null -> {
                    fromModel = true
                    guess
                }
                else -> continue
            }
            val clipped = Rect(rect.left, rect.top, rect.right, rect.bottom).apply {
                left = left.coerceIn(0, width)
                top = top.coerceIn(0, height)
                right = right.coerceIn(0, width)
                bottom = bottom.coerceIn(0, height)
            }
            if (clipped.width() < 4 || clipped.height() < 4) continue
            // Only deduplicate extra (SFX/artwork) entries against already-accepted boxes.
            // Main bubble entries in no-OCR mode all have empty ids and must NOT be filtered here.
            if (entry.fromExtra && accepted.any { it.overlapsMostly(clipped) }) continue

            entry.ids.forEach { id -> if (id in 1..blocks.size) used += id }
            if (entry.ids.size > 1) mergedBoxes++
            accepted += clipped

            val original = entry.source?.takeIf { it.isNotBlank() }
                ?: sources.joinToString(" ") { it.text }

            // In no-OCR mode, try to anchor the bounding box to the real bubble outline.
            val (finalBox, finalExactBox) = if (blocks.isEmpty() && bitmap != null && fromModel) {
                anchorVisionGuessWithoutOcr(bitmap, clipped, entry.isSfx)
            } else {
                clipped to fromModel
            }

            bubbles += TranslatedBubble(
                id = bubbles.size,
                originalText = original,
                translatedText = cleanMangaText(entry.translated),
                boundingBox = finalBox,
                isSfx = entry.isSfx,
                exactBox = finalExactBox,
            )
        }

        if (bubbles.isEmpty()) {
            // Every box was rejected: that is a valid answer for a page with no
            // readable dialogue. No answer at all is a failure worth retrying.
            if (entries.isEmpty()) {
                throw TranslationFailure("The model recognised no text on this page", raw)
            }
            return VisionResult(emptyList(), 0, blocks.size)
        }

        return VisionResult(
            bubbles = bubbles,
            mergedBoxes = mergedBoxes,
            discardedBoxes = blocks.indices.count { (it + 1) !in used },
        )
    }

    /** True when both rectangles cover most of the same area. */
    private fun Rect.overlapsMostly(other: Rect): Boolean {
        val overlapX = min(right, other.right) - max(left, other.left)
        val overlapY = min(bottom, other.bottom) - max(top, other.top)
        if (overlapX <= 0 || overlapY <= 0) return false
        val overlap = overlapX.toLong() * overlapY
        val smaller = min(width().toLong() * height(), other.width().toLong() * other.height())
            .coerceAtLeast(1L)
        return overlap * 10 >= smaller * 6
    }

    private fun Rect.isPlausibleBubble(pageWidth: Int, pageHeight: Int): Boolean {
        if (width() < pageWidth * 0.008f || height() < pageHeight * 0.008f) return false
        if (width().toFloat() * height() > pageWidth.toFloat() * pageHeight * 0.35f) return false
        val aspect = width().toFloat() / height()
        return aspect in 0.08f..12f
    }

    private fun Rect.expanded(pageWidth: Int, pageHeight: Int): Rect {
        val dx = max(pageWidth / 40, 4)
        val dy = max(pageHeight / 40, 4)
        return Rect(left - dx, top - dy, right + dx, bottom + dy)
    }

    private fun findVisionArray(root: JSONObject): JSONArray? {
        val preferred = listOf("bubbles", "panels", "boxes", "results", "translations", "items")
        preferred.firstNotNullOfOrNull { root.optJSONArray(it) }?.let { return it }
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            // "extra" is not a list of numbered balloons.
            if (key.equals("extra", ignoreCase = true) || key.equals("extras", ignoreCase = true)) continue
            val value = root.opt(key)
            if (value is JSONArray) return value
        }
        // A single balloon returned without the wrapper object.
        if (root.has("ids") || root.has("id")) return JSONArray().put(root)
        return null
    }

    private fun readIds(item: JSONObject): List<Int> {
        val rawIds = item.opt("ids") ?: item.opt("box_ids") ?: item.opt("boxes")
        if (rawIds is String) {
            return rawIds.split(',', ' ', '[', ']', ';').mapNotNull { it.trim().toIntOrNull() }
        }
        val array = rawIds as? JSONArray
        if (array != null) {
            val ids = mutableListOf<Int>()
            for (i in 0 until array.length()) {
                val id = array.optInt(i, -1).takeIf { it > 0 }
                    ?: array.optString(i).toIntOrNull()
                    ?: continue
                ids += id
            }
            return ids
        }
        val single = item.optInt("id", -1).takeIf { it > 0 }
            ?: item.optString("id").toIntOrNull()
        return listOfNotNull(single)
    }

    private fun readText(item: JSONObject): String = listOf("text", "translated", "translation", "es")
        .firstNotNullOfOrNull { key -> item.optString(key).takeIf { it.isNotBlank() } }
        ?: ""

    private fun readSource(item: JSONObject): String? = listOf("source", "original", "原文")
        .firstNotNullOfOrNull { key -> item.optString(key).takeIf { it.isNotBlank() } }

    /** Onomatopoeia is lettered differently: small and sideways, with no balloon. */
    private fun readSfx(item: JSONObject): Boolean {
        val kind = item.optString("kind")
            .ifBlank { item.optString("type") }
            .trim()
            .lowercase()
        return kind == "sfx" || kind == "onomatopoeia" || kind == "sound" ||
            item.optBoolean("sfx", false)
    }

    private fun readNormalizedRect(item: JSONObject, width: Int, height: Int): Rect? {
        val array = item.optJSONArray("box") ?: item.optJSONArray("bbox")
        if (array != null) return readNormalizedRect(array, width, height)
        val box = item.optJSONObject("box") ?: item.optJSONObject("bbox") ?: return null
        return readNormalizedRect(
            JSONArray().apply {
                put(box.optDouble("ymin", Double.NaN))
                put(box.optDouble("xmin", Double.NaN))
                put(box.optDouble("ymax", Double.NaN))
                put(box.optDouble("xmax", Double.NaN))
            },
            width,
            height,
        )
    }

    /** Reads the normalised [ymin, xmin, ymax, xmax] rectangle a model returns. */
    private fun readNormalizedRect(array: JSONArray?, width: Int, height: Int): Rect? {
        if (array == null || array.length() < 4) return null
        val values = DoubleArray(4) { i -> array.optDouble(i, Double.NaN) }
        if (values.any { it.isNaN() }) return null

        fun px(value: Double, size: Int): Int = (value / 1000.0 * size).roundToInt().coerceIn(0, size)

        val top = px(min(values[0], values[2]), height)
        val bottom = px(max(values[0], values[2]), height)
        val left = px(min(values[1], values[3]), width)
        val right = px(max(values[1], values[3]), width)
        if (right - left < width * 0.01f || bottom - top < height * 0.01f) return null
        if (right - left > width * 0.95f && bottom - top > height * 0.95f) return null
        return Rect(left, top, right, bottom)
    }

    private fun extractJsonObject(raw: String): JSONObject {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.substringAfter('\n', text).substringBeforeLast("```").trim()
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end <= start) {
            throw TranslationFailure("The model reply is not valid JSON", raw)
        }
        return JSONObject(text.substring(start, end + 1))
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
