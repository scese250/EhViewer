package com.hippo.ehviewer.translator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    /**
     * Round-robin cursor over the API keys. Each request starts on the key after
     * the previous one, so two pages running at the same time never share a key.
     */
    private val keyCursor = AtomicInteger(0)

    /**
     * One slot per API key. The keys split the pages between them instead of
     * standing by as a backup, so five keys translate five pages at once.
     */
    private val slotLock = Any()
    private var slotCount = 0
    private var slots = Semaphore(1)

    /**
     * Preparing a page costs a full-size ARGB copy. Only a couple are built at
     * the same time so a burst of parallel pages cannot run out of memory; the
     * requests themselves still run one per key.
     */
    private val annotationSlots = Semaphore(2)

    private fun slotsFor(keys: Int): Semaphore = synchronized(slotLock) {
        val wanted = keys.coerceAtLeast(1)
        if (wanted != slotCount) {
            slotCount = wanted
            slots = Semaphore(wanted)
        }
        slots
    }

    /** Reported before each expensive step so the UI can show what is running. */
    enum class Phase { VISION, }

    /** Largest side sent to the model: enough to read balloons, small enough to stay fast. */
    private const val VISION_MAX_EDGE = 1600
    private const val VISION_JPEG_QUALITY = 88
    private const val GEMINI_READ_TIMEOUT_MS = 15_000
    private const val GEMINI_VISION_READ_TIMEOUT_MS = 60_000
    private const val MAX_EXTRA_BUBBLES = 4

    /**
     * The full answer allowance of the model (reasoning included). Asking for
     * all of it means a page is never cut short, so no second request is needed
     * to finish a reply that ran out of room.
     */
    private const val MAX_OUTPUT_TOKENS = 65_536

    /** Older models cap the answer lower; a rejected limit falls back to this. */
    private const val FALLBACK_OUTPUT_TOKENS = 8_192

    /** A long sentence marked as SFX is a model slip: draw it as a normal balloon. */
    private const val MAX_SFX_LENGTH = 24
    private const val GRID_CELLS = 20

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

        var bubbles: List<TranslatedBubble> = emptyList()
        var engineUsed = "Gemini"

        // The model is the only engine: it reads the page, measures every
        // balloon and letters the translation in a single call. There is no
        // on-device OCR and no text-only rescue any more; the other API keys are
        // not a fallback but parallel workers on the following pages.
        suspend fun readPage(): VisionResult = translateWithGeminiVision(
            bitmap = bitmap,
            sourceLang = sourceLang,
            targetLang = targetLang,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            apiKeys = geminiApiKeys,
            model = geminiModel,
            thinkingLevel = thinkingLevel,
        )

        val transTime = measureTimeMillis {
            onPhase(Phase.VISION)
            // One request per page. An answer with no balloons comes back as an
            // empty result and the reader retries it, so every retry of a page
            // is counted in the same place instead of here and there.
            val result = readPage()
            bubbles = result.bubbles
            engineUsed = "Gemini (${geminiModel.removePrefix("gemini-")})"
        }

        PageTranslation(
            pageIndex = pageIndex,
            bubbles = bubbles,
            ocrDurationMs = 0L,
            translateDurationMs = transTime,
            totalDurationMs = transTime,
            engineName = engineUsed,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
        )
    }

    private fun cleanMangaText(text: String): String = text
        .replace(Regex("[\\r\\n]+"), " ")
        .replace(Regex("([¿¡])\\s+"), "$1")
        .replace(Regex("\\s+([!?,.:;])"), "$1")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * The model is told to answer "REJECT" for a balloon it cannot read. Such
     * balloons are dropped instead of being drawn with unreadable glyphs.
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
        if (apiKeys.isEmpty()) error("No API keys available")

        var lastError: Exception? = null
        val totalKeys = apiKeys.size
        val generationConfig = request.optJSONObject("generationConfig")
        // Older models reject thinkingConfig as an unknown field. Retry the
        // same key once without it instead of failing the whole page.
        var retriedWithoutThinking = generationConfig?.has("thinkingConfig") != true
        // A model with a smaller cap rejects the full allowance; the same key is
        // asked once more with a limit it accepts instead of failing the page.
        var retriedSmallerOutput = false
        val askedOutputTokens = generationConfig?.optInt("maxOutputTokens", 0) ?: 0

        // Each call starts on the key after the previous one, so the pages that
        // run at the same time work on different keys.
        val firstKey = keyCursor.getAndIncrement().mod(totalKeys)
        var attempt = 0
        while (attempt < totalKeys) {
            val keyIndex = (firstKey + attempt).mod(totalKeys)
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
                if (responseCode == 400 && !retriedSmallerOutput &&
                    askedOutputTokens > FALLBACK_OUTPUT_TOKENS &&
                    errorText.replace("_", "").contains("maxoutputtokens", ignoreCase = true)
                ) {
                    retriedSmallerOutput = true
                    generationConfig?.put("maxOutputTokens", FALLBACK_OUTPUT_TOKENS)
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
    )

    private class VisionEntry(
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

    private data class RawBox(val y1: Float, val x1: Float, val y2: Float, val x2: Float)

    /** One complete `{…}` of a model answer, with where it sits in the reply. */
    private class ReplyEntry(val start: Int, val end: Int, val value: JSONObject)

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
     * Waits for a free key before touching the page. Every key works on one page
     * at a time, so the pages the reader queues translate in parallel, one per
     * key, and no request competes with another for the same quota.
     */
    private suspend fun translateWithGeminiVision(
        bitmap: Bitmap,
        sourceLang: String,
        targetLang: String,
        systemPrompt: String,
        userPrompt: String = "",
        apiKeys: List<String>,
        model: String,
        thinkingLevel: String,
    ): VisionResult = slotsFor(apiKeys.size).withPermit {
        translateWithGeminiVisionOnKey(
            bitmap = bitmap,
            sourceLang = sourceLang,
            targetLang = targetLang,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            apiKeys = apiKeys,
            model = model,
            thinkingLevel = thinkingLevel,
        )
    }

    /**
     * Sends the whole page to the vision model, which reads the text, measures
     * every balloon and letters the translation in a single call.
     */
    private suspend fun translateWithGeminiVisionOnKey(
        bitmap: Bitmap,
        sourceLang: String,
        targetLang: String,
        systemPrompt: String,
        userPrompt: String = "",
        apiKeys: List<String>,
        model: String,
        thinkingLevel: String,
    ): VisionResult = withContext(Dispatchers.IO) {
        // The coordinate grid is drawn so the model has a visual ruler to report
        // accurate coordinates.
        val (imageBase64, encodedImage) = annotationSlots.withPermit {
            val annotated = try {
                annotateForVision(bitmap)
            } catch (e: OutOfMemoryError) {
                error("Not enough memory to prepare the image")
            }
            val scale = min(1f, VISION_MAX_EDGE.toFloat() / max(bitmap.width, bitmap.height))
            val size = EncodedVisionImage(
                base64 = "",
                sentWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                sentHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            )
            val base64 = try {
                ByteArrayOutputStream().use { stream ->
                    if (!annotated.compress(Bitmap.CompressFormat.JPEG, VISION_JPEG_QUALITY, stream)) {
                        error("No se pudo comprimir la imagen")
                    }
                    Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                }
            } finally {
                annotated.recycle()
            }
            base64 to size
        }

        suspend fun callModel(): JSONObject {
            val requestJson = JSONObject().apply {
                val parts = JSONArray().apply {
                    // The page is the user turn: the image, then any extra instructions.
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
                        // Thinking is charged to this same limit, so the whole
                        // allowance is requested instead of a per-level share.
                        put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                        putThinkingConfig(thinkingLevel)
                    },
                )
            }
            return postGemini(
                apiKeys,
                model,
                requestJson,
                readTimeoutFor(thinkingLevel, GEMINI_VISION_READ_TIMEOUT_MS),
            )
        }

        parseVisionResult(
            raw = candidateText(callModel()),
            width = bitmap.width,
            height = bitmap.height,
            maxExtras = MAX_EXTRA_BUBBLES * 2,
            sentWidth = encodedImage.sentWidth,
            sentHeight = encodedImage.sentHeight,
        )
    }

    private fun annotateForVision(bitmap: Bitmap): Bitmap {
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

        return output
    }

    /**
     * Ruler grid (0-1000 on both axes) so the model can report the balloon
     * rectangles itself in normalised coordinates.
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
        width: Int,
        height: Int,
        maxExtras: Int = MAX_EXTRA_BUBBLES,
        sentWidth: Int = width,
        sentHeight: Int = height,
    ): VisionResult {
        val root = readReply(raw)
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
            val translated = readText(item)
            if (translated.isBlank()) continue
            if (isReject(translated)) {
                // Explicit rejection: this balloon is never drawn.
                entries += VisionEntry(null, "", null)
                continue
            }
            entries += VisionEntry(
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
                source = readSource(item),
                translated = translated,
                rect = rect,
                isSfx = readSfx(item) && translated.trim().length <= MAX_SFX_LENGTH,
                fromExtra = true,
            )
            extraCount++
        }

        val accepted = mutableListOf<Rect>()
        val bubbles = mutableListOf<TranslatedBubble>()

        for (entry in entries) {
            if (entry.translated.isBlank()) continue

            // The model measures every balloon itself; a rectangle that does not
            // look like one is dropped instead of landing on the artwork.
            val rect = entry.rect?.takeIf { it.isPlausibleBubble(width, height) } ?: continue
            val clipped = Rect(rect.left, rect.top, rect.right, rect.bottom).apply {
                left = left.coerceIn(0, width)
                top = top.coerceIn(0, height)
                right = right.coerceIn(0, width)
                bottom = bottom.coerceIn(0, height)
            }
            if (clipped.width() < 4 || clipped.height() < 4) continue
            // Only deduplicate extra (SFX/artwork) entries against already-accepted boxes.
            if (entry.fromExtra && accepted.any { it.overlapsMostly(clipped) }) continue

            accepted += clipped
            bubbles += TranslatedBubble(
                id = bubbles.size,
                originalText = entry.source?.takeIf { it.isNotBlank() }.orEmpty(),
                translatedText = cleanMangaText(entry.translated),
                boundingBox = clipped,
                isSfx = entry.isSfx,
                // The model measures the balloon interior itself, so the box is
                // used as-is and the text is centred inside it.
                exactBox = true,
            )
        }

        if (bubbles.isEmpty()) {
            // Every entry was rejected, or the answer had none: that is a valid
            // answer for a page with no readable dialogue. It is never cached, and
            // the reader asks again a couple of times before accepting it, so a
            // page the lite model skipped is not left blank.
            return VisionResult(emptyList())
        }

        return VisionResult(bubbles = bubbles)
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

    /**
     * Reads the answer into the `{"bubbles": […], "extra": […]}` wrapper the
     * rest of the parser expects.
     *
     * A lite model sometimes repeats a closing brace, wraps the list in an array
     * or gets its answer cut by the token limit. Slicing the text from the first
     * `{` to the last `}` turned any of those into a failed page, so the answer
     * is walked instead: every entry object that arrived complete is kept, the
     * unbalanced rest is dropped, and the entries are rebuilt in page order.
     */
    private fun readReply(raw: String): JSONObject {
        val text = stripCodeFences(raw)
        if (text.startsWith("{")) {
            runCatching { JSONObject(text) }.getOrNull()?.let { return it }
        } else {
            runCatching { JSONArray(text) }.getOrNull()?.let { return JSONObject().put("bubbles", it) }
        }

        val repaired = escapeControlCharsInsideStrings(text)
        val found = mutableListOf<ReplyEntry>()
        val open = ArrayDeque<Int>()
        var inString = false
        var escaped = false
        for (index in repaired.indices) {
            val ch = repaired[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> open.addLast(index)
                '}' -> open.removeLastOrNull()?.let { start ->
                    parseEntry(repaired.substring(start, index + 1))?.let { entry ->
                        found += ReplyEntry(start, index, entry)
                    }
                }
            }
        }
        if (found.isEmpty()) {
            // Nothing entry-shaped, but the wrapper may still be a complete JSON
            // object wrapped in prose: keep the old slice as a last resort.
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start != -1 && end > start) {
                runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()?.let { return it }
            }
            throw TranslationFailure("The model reply is not valid JSON", raw)
        }

        // An object that merely holds other entries (the wrapper, or a box object
        // the model nested) is not a balloon: only the innermost entries count.
        val entries = found.filter { entry ->
            found.none { other ->
                other !== entry && other.start > entry.start && other.end < entry.end
            }
        }

        val bubbles = JSONArray()
        val extras = JSONArray()
        for (entry in entries.map { it.value }) {
            val hasIds = entry.has("ids") || entry.has("box_ids") || entry.has("boxes")
            if (hasIds) bubbles.put(entry) else extras.put(entry)
        }
        return JSONObject().apply {
            put("bubbles", bubbles)
            put("extra", extras)
        }
    }

    /** One `{…}` slice of the answer, or null when it is not a balloon entry. */
    private fun parseEntry(slice: String): JSONObject? {
        val cleaned = slice.replace(Regex(",\\s*([}\\]])"), "$1")
        val entry = runCatching { JSONObject(cleaned) }.getOrNull() ?: return null
        val hasBox = entry.has("box") || entry.has("box_2d") || entry.has("bbox") ||
            entry.has("ids") || entry.has("box_ids")
        if (!hasBox) return null
        val hasText = readText(entry).isNotBlank() || !readSource(entry).isNullOrBlank()
        return entry.takeIf { hasText }
    }

    private fun stripCodeFences(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.substringAfter('\n', text)
            text = text.substringBeforeLast("```", text)
        }
        return text.trim()
    }

    /**
     * A raw newline or tab inside a string is enough to make the JSON unreadable.
     * The characters are escaped in place instead of failing the page.
     */
    private fun escapeControlCharsInsideStrings(text: String): String {
        val out = StringBuilder(text.length + 16)
        var inString = false
        var escaped = false
        for (ch in text) {
            if (!inString) {
                out.append(ch)
                if (ch == '"') inString = true
                continue
            }
            when {
                escaped -> {
                    out.append(ch)
                    escaped = false
                }
                ch == '\\' -> {
                    out.append(ch)
                    escaped = true
                }
                ch == '"' -> {
                    out.append(ch)
                    inString = false
                }
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch < ' ' -> out.append(' ')
                else -> out.append(ch)
            }
        }
        return out.toString()
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
                    errors.add("Key #${index + 1}: $err")
                }
            }.onFailure {
                errors.add("Key #${index + 1}: ${it.message}")
            }
        }

        if (successCount > 0) {
            Result.success("$successCount of ${apiKeys.size} keys work with $model")
        } else {
            Result.failure(IllegalStateException(errors.joinToString("\n")))
        }
    }
}
