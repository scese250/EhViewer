package com.hippo.ehviewer.translator

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Translation preferences for the gallery reader.
 *
 * The UI for these values lives exclusively inside the reader (translation
 * button in the bottom bar), not in the global application settings.
 */
class TranslatorSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("manga_translator_prefs", Context.MODE_PRIVATE)

    init {
        // The languages are written by hand now. Older builds stored a code
        // ("ja", "es"...), so turn it into a name once to avoid showing it.
        if (!prefs.getBoolean("languages_v2", false)) {
            prefs.edit()
                .putString("source_lang", normalizeLanguage(prefs.getString("source_lang", null), "Japanese"))
                .putString("target_lang", normalizeLanguage(prefs.getString("target_lang", null), "English"))
                .putBoolean("languages_v2", true)
                .apply()
        }
        // The anti-overlap used to leave 12dp of air between the neighbours;
        // it now only separates until the edges touch.
        if (!prefs.getBoolean("collision_v2", false)) {
            prefs.edit()
                .putFloat("collision_gap", 0f)
                .putBoolean("collision_v2", true)
                .apply()
        }
    }

    var geminiApiKeysRaw by mutableStateOf(prefs.getString("gemini_api_keys", "").orEmpty())
    var geminiModel by mutableStateOf(
        prefs.getString("gemini_model", DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL,
    )

    /**
     * Models pinned to the quick selector. The current model can be picked from
     * here, and every entry can be removed; the list is empty only if the user
     * deletes them all.
     */
    var geminiModels by mutableStateOf(
        prefs.getString("gemini_models", null)?.let { raw ->
            raw.split('\n')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        } ?: listOf(DEFAULT_GEMINI_MODEL),
    )

    /** Language names as the user typed them, e.g. "Japanese" / "English". */
    var sourceLang by mutableStateOf(prefs.getString("source_lang", "Japanese") ?: "Japanese")
    var targetLang by mutableStateOf(prefs.getString("target_lang", "English") ?: "English")
    var globalBubbleScale by mutableFloatStateOf(prefs.getFloat("global_bubble_scale", 1.0f))
    var bubbleOpacity by mutableFloatStateOf(prefs.getFloat("bubble_opacity", 1.0f))
    var autoTranslate by mutableStateOf(prefs.getBoolean("auto_translate", false))
    var batchSize by mutableIntStateOf(prefs.getInt("batch_size", 3))

    /**
     * Reasoning budget requested from Gemini ("minimal", "low", "medium" or
     * "high"). Higher levels read the page better and cost more time.
     */
    var thinkingLevel by mutableStateOf(
        prefs.getString("thinking_level", THINKING_MINIMAL) ?: THINKING_MINIMAL,
    )

    /**
     * Instructions sent as the Gemini system prompt. The page image and the
     * layout task travel in the user turn, so the translator's own rules
     * (pronouns, H vocabulary, signs, REJECT) live here and stay editable.
     */
    var geminiSystemPrompt by mutableStateOf(
        run {
            val saved = prefs.getString("gemini_system_prompt", null)
            val version = prefs.getInt("gemini_system_prompt_version", 0)
            if (saved.isNullOrBlank() || version < 2 || saved.contains("You are the translator and letterer of a manga page")) {
                prefs.edit()
                    .putString("gemini_system_prompt", DEFAULT_SYSTEM_PROMPT)
                    .putInt("gemini_system_prompt_version", 2)
                    .apply()
                DEFAULT_SYSTEM_PROMPT
            } else {
                saved
            }
        },
    )

    /**
     * Extra instructions sent in the user turn, together with the page and the
     * layout task. Empty by default: the system prompt already carries the rules.
     */
    var geminiUserPrompt by mutableStateOf(
        prefs.getString("gemini_user_prompt", "").orEmpty(),
    )

    var bubbleOrientation by mutableStateOf(
        prefs.getString("bubble_orientation", ORIENTATION_VERTICAL) ?: ORIENTATION_VERTICAL,
    )

    /**
     * Draw the onomatopoeia the model returns. Turning it off keeps them in the
     * cached translation (nothing is translated again) but hides them.
     */
    var showSfx by mutableStateOf(prefs.getBoolean("show_sfx", true))
    var bubbleShape by mutableStateOf(
        prefs.getString("bubble_shape", SHAPE_OVAL) ?: SHAPE_OVAL,
    )

    /** White filled balloon or only a thick white outline around the letters. */
    var bubbleStyle by mutableStateOf(
        prefs.getString("bubble_style", STYLE_FILL) ?: STYLE_FILL,
    )
    var outlineWidth by mutableFloatStateOf(prefs.getFloat("outline_width", 3.0f))
    var bubblePadding by mutableStateOf(
        prefs.getString("bubble_padding", PADDING_COMPACT) ?: PADDING_COMPACT,
    )

    var uppercaseScanlation by mutableStateOf(prefs.getBoolean("uppercase_scanlation", true))
    var preventCollisions by mutableStateOf(prefs.getBoolean("prevent_collisions", true))
    var collisionGap by mutableFloatStateOf(prefs.getFloat("collision_gap", 0f))
    var collisionIterations by mutableIntStateOf(prefs.getInt("collision_iterations", 40))
    var maxCollisionShiftRatio by mutableFloatStateOf(prefs.getFloat("max_collision_shift_ratio", 0.30f))
    var borderThickness by mutableFloatStateOf(prefs.getFloat("border_thickness", 1.2f))
    var isManipulationMode by mutableStateOf(prefs.getBoolean("is_manipulation_mode", false))

    /**
     * Font size used by every balloon. The model only measures rectangles; the
     * size of the letters is this value, so a small bubble can never end up
     * with huge text and the page keeps one consistent lettering size.
     */
    var standardFontSizeSp by mutableFloatStateOf(prefs.getFloat("standard_font_size_sp", 12f))

    /**
     * Incremented every time the reader settings are saved, so the reader
     * restarts its translation scheduler with the new values. It is not a cache
     * key: a settings change never throws saved pages away.
     */
    var revision by mutableIntStateOf(0)
        private set

    fun getGeminiApiKeys(): List<String> = geminiApiKeysRaw
        .split('\n', ',', ';')
        .map { it.trim() }
        .filter { it.isNotBlank() }

    fun addGeminiModel(id: String) {
        val model = id.trim()
        if (model.isEmpty() || model in geminiModels) return
        geminiModels = geminiModels + model
    }

    fun removeGeminiModel(id: String) {
        geminiModels = geminiModels - id
    }

    fun save() {
        // A field left empty must not reach the prompt: fall back to the
        // defaults, and turn a bare code into its name.
        sourceLang = normalizeLanguage(sourceLang, "Japanese")
        targetLang = normalizeLanguage(targetLang, "English")
        prefs.edit()
            .putString("gemini_api_keys", geminiApiKeysRaw)
            .putString("gemini_model", geminiModel)
            .putString("gemini_models", geminiModels.joinToString("\n"))
            .putString("gemini_user_prompt", geminiUserPrompt)
            .putString("source_lang", sourceLang)
            .putString("target_lang", targetLang)
            .putString("gemini_system_prompt", geminiSystemPrompt)
            .putInt("gemini_system_prompt_version", 2)
            .putString("thinking_level", thinkingLevel)
            .putString("bubble_style", bubbleStyle)
            .putFloat("outline_width", outlineWidth)
            .putString("bubble_orientation", bubbleOrientation)
            .putString("bubble_shape", bubbleShape)
            .putString("bubble_padding", bubblePadding)
            .putBoolean("uppercase_scanlation", uppercaseScanlation)
            .putBoolean("prevent_collisions", preventCollisions)
            .putFloat("collision_gap", collisionGap)
            .putInt("collision_iterations", collisionIterations)
            .putFloat("max_collision_shift_ratio", maxCollisionShiftRatio)
            .putBoolean("is_manipulation_mode", isManipulationMode)
            .putFloat("border_thickness", borderThickness)
            .putFloat("standard_font_size_sp", standardFontSizeSp)
            .putFloat("global_bubble_scale", globalBubbleScale)
            .putFloat("bubble_opacity", bubbleOpacity)
            .putBoolean("show_sfx", showSfx)
            .putBoolean("auto_translate", autoTranslate)
            .putInt("batch_size", batchSize)
            .apply()

        revision++
    }

    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-flash-lite-latest"

        /** No extra user instructions by default. */
        const val DEFAULT_USER_PROMPT = ""

        const val THINKING_MINIMAL = "minimal"
        const val THINKING_LOW = "low"
        const val THINKING_MEDIUM = "medium"
        const val THINKING_HIGH = "high"

        val THINKING_LEVELS = listOf(
            "Minimal" to THINKING_MINIMAL,
            "Low" to THINKING_LOW,
            "Medium" to THINKING_MEDIUM,
            "High" to THINKING_HIGH,
        )

        const val ORIENTATION_VERTICAL = "vertical"
        const val ORIENTATION_HORIZONTAL = "horizontal"
        const val ORIENTATION_AUTO = "auto"

        const val SHAPE_OVAL = "oval"
        const val SHAPE_ROUNDED = "rounded"
        const val SHAPE_BORDERLESS = "borderless"

        /** White balloon with black text. */
        const val STYLE_FILL = "fill"

        /** No balloon: black letters with a thick white outline. */
        const val STYLE_OUTLINE = "outline"

        const val PADDING_COMPACT = "compact"
        const val PADDING_NORMAL = "normal"
        const val PADDING_SPACIOUS = "spacious"

        /**
         * Editable rules sent as the system prompt. They are language neutral,
         * so the same text works for every target language.
         */
        val DEFAULT_SYSTEM_PROMPT = """
            |You are the OCR and direct text overlay lettering engine for this manga page: the attached image is the complete page.
            |Your job is to read every block of Japanese text, translate it to {lang output}, and provide the exact bounding box of the Japanese text characters so the translated {lang output} text is rendered directly on top of the original Japanese text.
            |
            |Coordinates:
            |- Use normalised coordinates 0-1000 for both axes (0,0 = top-left of the image, 1000,1000 = bottom-right).
            |- The "box" field is [ymin, xmin, ymax, xmax].
            |
            |Rules:
            |1. Return one entry per block of dialogue, monologue, or caption, in Japanese manga reading order (right to left, top to bottom).
            |2. "ids" must always be empty ([]).
            |3. "box": [ymin, xmin, ymax, xmax] must tightly enclose the JAPANESE CHARACTERS of each dialogue block:
            |   - Enclose all vertical columns of that sentence together (from the top of the highest character to the bottom of the lowest, and from the leftmost column to the rightmost column).
            |   - The app stamps the translated {lang output} text directly ON TOP of the original Japanese characters with a thick white outline.
            |   - Do NOT report the speech balloon interior or padding. Do NOT place the box over empty space, and NEVER place the box over a character's face or body unless the Japanese text is literally drawn over them.
            |   - The box does not auto-grow or expand: it is the exact placement target where the translated text will sit directly over the original Japanese text.
            |4. If a sentence spans across multiple adjacent vertical columns in the same bubble, join them into a single entry covering all those columns.
            |5. Onomatopoeia painted on artwork must be returned in "extra" with "kind": "sfx" and "box" tightly covering the sound effect characters.
            |6. Ignore non-manga text: watermarks, scanlation credits, page numbers, store logos, and URLs.
            |7. Do not add notes, romanisation or explanations.
            |
            |Reply with raw JSON only, without markdown fences or commentary:
            |{"bubbles":[{"ids":[],"box":[ymin,xmin,ymax,xmax],"source":"japanese text as printed","text":"{lang output} translation","kind":"dialogue"}],"extra":[{"box":[ymin,xmin,ymax,xmax],"source":"...","text":"...","kind":"sfx"}]}
        """.trimMargin()

        /** Codes older builds stored, so the language fields show a name. */
        private val LEGACY_LANGUAGE_CODES = mapOf(
            "ja" to "Japanese",
            "en" to "English",
            "es" to "Spanish",
            "pt" to "Portuguese",
            "fr" to "French",
            "de" to "German",
            "it" to "Italian",
            "ru" to "Russian",
            "zh" to "Chinese",
            "ko" to "Korean",
        )

        fun normalizeLanguage(value: String?, fallback: String): String {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return fallback
            return LEGACY_LANGUAGE_CODES[text.lowercase()] ?: text
        }

        /** True while the source is Japanese, spelled as a code or as a name. */
        fun isJapanese(language: String): Boolean {
            val text = language.trim()
            return text.equals("ja", ignoreCase = true) ||
                text.equals("japanese", ignoreCase = true) ||
                text.contains("日本")
        }

        fun getLanguageName(code: String): String = when (code.lowercase()) {
            "ja" -> "Japanese"
            "es" -> "Spanish"
            "en" -> "English"
            "zh" -> "Chinese"
            "ko" -> "Korean"
            "pt" -> "Portuguese"
            "fr" -> "French"
            "de" -> "German"
            "it" -> "Italian"
            "ru" -> "Russian"
            else -> code
        }

        fun formatGeminiPrompt(
            template: String,
            sourceLang: String,
            targetLang: String,
        ): String {
            val srcName = getLanguageName(sourceLang)
            val tgtName = getLanguageName(targetLang)
            return template
                .replace("{lang input}", srcName, ignoreCase = true)
                .replace("{lang output}", tgtName, ignoreCase = true)
                .replace("{lang_input}", srcName, ignoreCase = true)
                .replace("{lang_output}", tgtName, ignoreCase = true)
                .replace("{source_lang}", srcName, ignoreCase = true)
                .replace("{target_lang}", tgtName, ignoreCase = true)
                .replace("{sourceLang}", srcName, ignoreCase = true)
                .replace("{targetLang}", tgtName, ignoreCase = true)
        }
    }
}
