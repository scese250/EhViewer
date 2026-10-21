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
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object PageTranslator {
    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    private val googleSemaphore = Semaphore(6)

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
        sourceLang: String = "ja",
        targetLang: String = "es",
        promptTemplate: String = TranslatorSettings.DEFAULT_PROMPT_TEMPLATE,
        systemPrompt: String = TranslatorSettings.DEFAULT_SYSTEM_PROMPT,
        engine: String = TranslatorSettings.ENGINE_GOOGLE,
        geminiApiKeys: List<String> = emptyList(),
        geminiModel: String = TranslatorSettings.DEFAULT_GEMINI_MODEL,
        grouping: BubbleGroupingConfig = BubbleGroupingConfig(),
        visionMode: Boolean = true,
        skipOcr: Boolean = false,
        onPhase: (Phase) -> Unit = {},
    ): PageTranslation = withContext(Dispatchers.Default) {
        val gemini = engine == TranslatorSettings.ENGINE_GEMINI && geminiApiKeys.isNotEmpty()
        // Without the local OCR there are neither hint boxes nor fallback text,
        // so this mode only makes sense when the vision model can be called.
        val ocrSkipped = skipOcr && gemini

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

        var rawBlocks: List<RawBlock> = emptyList()
        var ocrTime = 0L
        var mergedBlocks: List<RawBlock> = emptyList()
        if (!ocrSkipped) {
            onPhase(Phase.OCR)
            val (blocks, ms) = recognize()
            rawBlocks = blocks
            ocrTime = ms
            // Stitch the per-column OCR fragments of every balloon back
            // together. Without this a single vertical bubble is translated
            // twice and drawn as two overlapping boxes. Keep the raw blocks if
            // analysis fails so a detection bug can never break translation.
            mergedBlocks = runCatching {
                BubbleDetector.group(rawBlocks, bitmap, grouping)
            }.getOrElse { rawBlocks }
        }

        var bubbles: List<TranslatedBubble> = emptyList()
        var engineUsed = "Google"
        var mergedBoxes = 0
        var discardedBoxes = 0

        if (engine == TranslatorSettings.ENGINE_GEMINI && geminiApiKeys.isEmpty()) {
            engineUsed = "Google (sin claves de Gemini)"
        }

        val transTime = measureTimeMillis {
            if (gemini) {
                var translated = false
                var visionFailure: Exception? = null

                if (visionMode || ocrSkipped) {
                    onPhase(Phase.VISION)
                    try {
                        val result = translateWithGeminiVision(
                            bitmap = bitmap,
                            blocks = mergedBlocks,
                            noHints = ocrSkipped,
                            sourceLang = sourceLang,
                            targetLang = targetLang,
                            promptTemplate = promptTemplate,
                            systemPrompt = systemPrompt,
                            apiKeys = geminiApiKeys,
                            model = geminiModel,
                        )
                        bubbles = result.bubbles
                        mergedBoxes = result.mergedBoxes
                        discardedBoxes = result.discardedBoxes
                        engineUsed = if (ocrSkipped) {
                            "Gemini sin OCR (${geminiModel.removePrefix("gemini-")})"
                        } else {
                            "Gemini visión (${geminiModel.removePrefix("gemini-")})"
                        }
                        translated = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        visionFailure = e
                    }
                }

                if (!translated) {
                    // With the OCR skipped there is no text to fall back to:
                    // run ML Kit now so the rescue path can still translate.
                    if (ocrSkipped && rawBlocks.isEmpty()) {
                        onPhase(Phase.OCR)
                        val (blocks, ms) = recognize()
                        rawBlocks = blocks
                        ocrTime += ms
                        mergedBlocks = runCatching {
                            BubbleDetector.group(rawBlocks, bitmap, grouping)
                        }.getOrElse { rawBlocks }
                    }
                    onPhase(Phase.TRANSLATE)
                    try {
                        bubbles = translateWithGeminiText(
                            blocks = mergedBlocks,
                            sourceLang = sourceLang,
                            targetLang = targetLang,
                            promptTemplate = promptTemplate,
                            systemPrompt = systemPrompt,
                            apiKeys = geminiApiKeys,
                            model = geminiModel,
                        )
                        engineUsed = if (visionFailure != null) {
                            "Gemini texto (visión: ${visionFailure.message?.take(40)})"
                        } else {
                            "Gemini (${geminiModel.removePrefix("gemini-")})"
                        }
                        translated = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        engineUsed = "Google (Fallback: ${(visionFailure ?: e).message?.take(24)})"
                    }
                }

                if (!translated) {
                    bubbles = translateWithGoogleBatch(mergedBlocks, sourceLang, targetLang)
                }
            } else {
                onPhase(Phase.TRANSLATE)
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
            mergedBoxes = mergedBoxes,
            discardedBoxes = discardedBoxes,
        )
    }

    private suspend fun translateWithGeminiText(
        blocks: List<RawBlock>,
        sourceLang: String,
        targetLang: String,
        promptTemplate: String,
        systemPrompt: String,
        apiKeys: List<String>,
        model: String,
    ): List<TranslatedBubble> {
        val translated = translateWithGemini(
            texts = blocks.map { it.text },
            sourceLang = sourceLang,
            targetLang = targetLang,
            promptTemplate = promptTemplate,
            systemPrompt = systemPrompt,
            apiKeys = apiKeys,
            model = model,
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

    private suspend fun translateWithGoogleBatch(
        blocks: List<RawBlock>,
        sourceLang: String,
        targetLang: String,
    ): List<TranslatedBubble> = coroutineScope {
        blocks.mapIndexed { idx, block ->
            async(Dispatchers.IO) {
                // A page can have dozens of bubbles; firing them all at once
                // makes the endpoint throttle every request and the page takes
                // minutes. Keep a small pool so results still stream in order.
                val translated = googleSemaphore.withPermit {
                    translateTextGoogle(block.text, sourceLang, targetLang)
                }
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
                connectTimeout = 5000
                readTimeout = 5000
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
                connectTimeout = 5000
                readTimeout = 5000
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
        systemPrompt: String,
        apiKeys: List<String>,
        model: String,
    ): List<String?> = withContext(Dispatchers.IO) {
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
        """.trimIndent()

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
            put("systemInstruction", systemInstruction(systemPrompt, sourceLang, targetLang))
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

        val root = postGemini(apiKeys, model, requestJson, GEMINI_READ_TIMEOUT_MS)
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
            if (clean.isNotBlank() && (sourceLang != "ja" || !containsJapanese(clean))) {
                results[targetId] = clean
            }
        }

        // Anything the model skipped is recovered with the free endpoint, in
        // parallel, so one malformed reply never leaves a balloon empty.
        val missing = texts.indices.filter { results[it] == null && !rejected[it] }
        val recovered = coroutineScope {
            missing.map { idx ->
                async(Dispatchers.IO) {
                    idx to googleSemaphore.withPermit {
                        translateTextGoogle(texts[idx], sourceLang, targetLang)
                    }
                }
            }.awaitAll().toMap()
        }

        texts.indices.map { idx ->
            when {
                rejected[idx] -> null
                results[idx] != null -> results[idx]
                else -> recovered[idx]?.takeIf { it.isNotBlank() }
            }
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
                lastError = IllegalStateException("Gemini API ($responseCode): ${errorText.take(200)}")
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

        throw lastError ?: IllegalStateException("Todas las claves de API fallaron")
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
            error("Gemini bloqueó la imagen ($blockReason)")
        }

        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
            ?: error("Gemini no devolvió respuesta")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            ?: error("Gemini devolvió una respuesta vacía")

        val builder = StringBuilder()
        for (i in 0 until parts.length()) {
            parts.optJSONObject(i)?.optString("text")?.let(builder::append)
        }

        val text = builder.toString().trim()
        if (text.isEmpty()) {
            val reason = candidate.optString("finishReason")
            error("Gemini devolvió una respuesta vacía${if (reason.isNotBlank()) " ($reason)" else ""}")
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
    )

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
        promptTemplate: String,
        systemPrompt: String,
        apiKeys: List<String>,
        model: String,
    ): VisionResult = withContext(Dispatchers.IO) {
        val annotated = try {
            annotateForVision(bitmap, blocks)
        } catch (e: OutOfMemoryError) {
            error("Memoria insuficiente para preparar la imagen")
        }

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

        val instruction = TranslatorSettings.formatGeminiPrompt(
            template = promptTemplate,
            sourceLang = sourceLang,
            targetLang = targetLang,
        )
        val prompt = buildVisionPrompt(instruction, targetLang, blocks.size, noHints)

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
            put("systemInstruction", systemInstruction(systemPrompt, sourceLang, targetLang))
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.1)
                    put("responseMimeType", "application/json")
                    put("maxOutputTokens", 4096)
                    put(
                        "thinkingConfig",
                        JSONObject().apply {
                            // Same setting the text path already uses, so a model
                            // that rejected another level can't break vision mode.
                            put("thinkingLevel", "MINIMAL")
                        },
                    )
                },
            )
        }

        val root = postGemini(apiKeys, model, requestJson, GEMINI_VISION_READ_TIMEOUT_MS)
        parseVisionResult(
            raw = candidateText(root),
            blocks = blocks,
            width = bitmap.width,
            height = bitmap.height,
            maxExtras = if (noHints) MAX_EXTRA_BUBBLES * 2 else MAX_EXTRA_BUBBLES,
        )
    }

    private fun buildVisionPrompt(
        instruction: String,
        targetLang: String,
        boxCount: Int,
        noHints: Boolean = false,
    ): String = buildString {
        appendLine(instruction)
        appendLine()
        if (noHints) {
            appendLine("You are the OCR engine and the lettering engine for this manga page: the attached image is")
            appendLine("the complete page and it carries no machine boxes. Find every speech balloon and every")
            appendLine("caption box yourself, read the text that is really inside it, translate it and measure the")
            appendLine("area the translated text must cover. Nothing was detected for you, so a balloon you miss is")
            appendLine("a balloon that stays untranslated.")
        } else {
            appendLine("You are ALSO the OCR engine and the bubble layout engine for this manga page: the attached")
            appendLine("image is the complete page.")
            appendLine("A weak OCR has drawn numbered red boxes over the places where it thinks text may be.")
            appendLine("Those boxes are only hints; their text is not given to you and both their position and their")
            appendLine("size may be wrong. Look at the page yourself, read the text that is really inside each place,")
            appendLine("translate it, and measure the area the translated text must cover.")
        }
        appendLine("A blue grid is drawn over the page: 20 x 20 cells, each cell 50 units wide, with the")
        appendLine("normalised coordinate (0-1000) printed along all four edges. Use it as a ruler.")
        if (!noHints && boxCount == 0) {
            appendLine("This page has no numbered boxes: recover every speech balloon you can see with \"extra\".")
        }
        appendLine()
        appendLine("Rules:")
        appendLine("1. Return one entry per speech balloon of the finished page, in reading order")
        appendLine("   (Japanese manga: right to left, top to bottom).")
        if (noHints) {
            appendLine("2. There are no numbered boxes here: put every balloon in \"bubbles\" and leave \"ids\" empty")
            appendLine("   ([]). Never return two entries for the same balloon.")
        } else {
            appendLine("2. \"ids\" lists every numbered box that belongs to that balloon. If one balloon was split into")
            appendLine("   two boxes (for example 1 and 2), return a SINGLE entry with \"ids\": [1, 2] and the whole")
            appendLine("   text of the balloon. Never return two entries for the same balloon.")
        }
        appendLine("3. If a box has no readable dialogue (it landed on artwork, on a sound effect, or it is a")
        appendLine("   duplicate fragment of another box), do not translate it: answer exactly \"REJECT\" in its")
        appendLine("   text. Do not invent text for it.")
        appendLine("4. If a sentence continues from one box into another box of the same balloon, join both ids in one entry.")
        appendLine("5. Every entry must also carry \"box\": [ymin, xmin, ymax, xmax], the normalised rectangle")
        appendLine("   (0-1000) of the TEXT ITSELF, not of the balloon. Follow the lines of dialogue and leave out")
        appendLine("   the balloon padding, the balloon tail, the character's face and the neighbouring balloon.")
        appendLine("   The translation is centred inside that rectangle and, when the translated text is longer")
        appendLine("   than the original, it grows upwards and downwards around the centre on its own, so keep")
        appendLine("   the rectangle centred on the original text and cover only the text.")
        appendLine("6. A balloon that only holds a sound effect, or that has no text at all, gets no entry:")
        appendLine("   answer exactly \"REJECT\" for it.")
        appendLine("7. Ignore text outside balloons: credits, watermarks, page numbers, store logos, meta")
        appendLine("   commentary, shop signs, posters, book spines and text painted on the artwork.")
        appendLine("8. Onomatopoeia painted on the artwork is not a balloon: return it as an \"extra\" entry with")
        appendLine("   \"kind\": \"sfx\" and a short sound word (\"¡BAM!\", \"¡DOON!\"). It is lettered small and")
        appendLine("   sideways, so never wrap it in a sentence.")
        appendLine("9. Use the panel around each balloon to decide who is speaking: first or third person, and the")
        appendLine("   gender of the speaker. Keep the same person and gender for every line of the conversation.")
        appendLine("10. Do not add notes, romanisation or explanations.")
        appendLine()
        appendLine("Reply with JSON only, without prose or code fences:")
        append("{\"bubbles\":[{\"ids\":")
        append(if (noHints) "[]" else "[1]")
        append(",\"box\":[ymin,xmin,ymax,xmax],\"source\":\"text as printed\",")
        append("\"text\":\"")
        append(TranslatorSettings.getLanguageName(targetLang))
        append(" translation\",\"kind\":\"dialogue\"}],")
        append("\"extra\":[{\"box\":[ymin,xmin,ymax,xmax],\"source\":\"...\",\"text\":\"...\",\"kind\":\"sfx\"}]}")
        appendLine()
        appendLine("\"source\" is the text exactly as printed on the page. \"kind\" is \"dialogue\" for speech and")
        appendLine("\"sfx\" for onomatopoeia. \"extra\" is for balloons with no numbered box and for sound")
        appendLine("effects painted on the artwork. Use at most ${if (noHints) MAX_EXTRA_BUBBLES * 2 else MAX_EXTRA_BUBBLES} extra entries.")
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
    ): VisionResult {
        val root = runCatching { extractJsonObject(raw) }.getOrElse {
            JSONObject().put("bubbles", extractJsonArray(raw))
        }
        val entries = mutableListOf<VisionEntry>()

        val extras = root.optJSONArray("extra") ?: root.optJSONArray("extras") ?: JSONArray()
        val bubbleArray = findVisionArray(root)
            ?: if (extras.length() == 0) error("La IA no devolvió ninguna viñeta") else JSONArray()
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
                rect = readNormalizedRect(item, width, height),
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
            // A box without ids that sits almost exactly on an existing balloon
            // is a duplicate. A small touch is not: neighbouring balloons of a
            // page read without OCR often share a few pixels and are real.
            if (entry.ids.isEmpty() && accepted.any { it.overlapsMostly(clipped) }) continue

            entry.ids.forEach { id -> if (id in 1..blocks.size) used += id }
            if (entry.ids.size > 1) mergedBoxes++
            accepted += clipped

            val original = entry.source?.takeIf { it.isNotBlank() }
                ?: sources.joinToString(" ") { it.text }
            bubbles += TranslatedBubble(
                id = bubbles.size,
                originalText = original,
                translatedText = cleanMangaText(entry.translated),
                boundingBox = clipped,
                isSfx = entry.isSfx,
                exactBox = fromModel,
            )
        }

        if (bubbles.isEmpty()) {
            // Every box was rejected: that is a valid answer for a page with no
            // readable dialogue. No answer at all is a failure worth retrying.
            if (entries.isEmpty()) error("La IA no reconoció texto en esta página")
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
            error("Respuesta de la IA no válida: ${raw.take(120)}")
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
