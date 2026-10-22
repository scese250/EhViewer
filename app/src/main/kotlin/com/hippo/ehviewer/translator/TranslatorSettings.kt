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

    var engine by mutableStateOf(prefs.getString("engine", ENGINE_GOOGLE) ?: ENGINE_GOOGLE)
    var geminiApiKeysRaw by mutableStateOf(prefs.getString("gemini_api_keys", "").orEmpty())
    var geminiModel by mutableStateOf(
        prefs.getString("gemini_model", DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL,
    )
    var sourceLang by mutableStateOf(prefs.getString("source_lang", "ja") ?: "ja")
    var targetLang by mutableStateOf(prefs.getString("target_lang", "es") ?: "es")
    var globalBubbleScale by mutableFloatStateOf(prefs.getFloat("global_bubble_scale", 1.0f))
    var horizontalExpansion by mutableFloatStateOf(prefs.getFloat("horizontal_expansion", 1.20f))
    var globalFontScale by mutableFloatStateOf(prefs.getFloat("global_font_scale", 0.90f))
    var bubbleOpacity by mutableFloatStateOf(prefs.getFloat("bubble_opacity", 1.0f))
    var dynamicBubbleSize by mutableStateOf(prefs.getBoolean("dynamic_bubble_size", true))
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
        prefs.getString("gemini_system_prompt", DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT,
    )

    /**
     * Send the page with the OCR boxes drawn so Gemini reads the real text
     * instead of trusting the OCR transcription.
     */
    var geminiVision by mutableStateOf(prefs.getBoolean("gemini_vision", true))

    /**
     * Skips ML Kit and lets the vision model find the balloons itself. Faster
     * and immune to OCR failures, but without local boxes there is no safety
     * net: if the model misreads the page, nothing is drawn.
     */
    var skipOcr by mutableStateOf(prefs.getBoolean("skip_ocr", false))
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

    /**
     * Use the rectangle returned by the model verbatim: no widening, no scaling
     * and no collision shifting. Without this the translation drifts away from
     * the balloons the model measured.
     */
    var exactPlacement by mutableStateOf(prefs.getBoolean("exact_placement", true))
    var uppercaseScanlation by mutableStateOf(prefs.getBoolean("uppercase_scanlation", true))
    var preventCollisions by mutableStateOf(prefs.getBoolean("prevent_collisions", true))
    var collisionGap by mutableFloatStateOf(prefs.getFloat("collision_gap", 12f))
    var collisionIterations by mutableIntStateOf(prefs.getInt("collision_iterations", 40))
    var maxCollisionShiftRatio by mutableFloatStateOf(prefs.getFloat("max_collision_shift_ratio", 0.30f))
    var borderThickness by mutableFloatStateOf(prefs.getFloat("border_thickness", 1.2f))
    var isManipulationMode by mutableStateOf(prefs.getBoolean("is_manipulation_mode", false))

    // Bubble detection (OCR fragment grouping)
    var mergeBubbleColumns by mutableStateOf(prefs.getBoolean("merge_bubble_columns", true))
    var maxColumnGapRatio by mutableFloatStateOf(prefs.getFloat("max_column_gap_ratio", 0.35f))
    var minColumnOverlap by mutableFloatStateOf(prefs.getFloat("min_column_overlap", 0.55f))
    var borderDarkRatio by mutableFloatStateOf(prefs.getFloat("border_dark_ratio", 0.40f))

    // Text fitting inside the original balloon
    var fitTextInsideBubble by mutableStateOf(prefs.getBoolean("fit_text_inside_bubble", true))

    /**
     * Font size used by every balloon. The model only measures rectangles; the
     * size of the letters is this value, so a small bubble can never end up
     * with huge text and the page keeps one consistent lettering size.
     */
    var standardFontSizeSp by mutableFloatStateOf(prefs.getFloat("standard_font_size_sp", 12f))
    var minFontSizeSp by mutableFloatStateOf(prefs.getFloat("min_font_size_sp", 6.5f))
    var maxFontSizeSp by mutableFloatStateOf(prefs.getFloat("max_font_size_sp", 16f))
    var maxHeightExpansion by mutableFloatStateOf(prefs.getFloat("max_height_expansion", 1.6f))

    /**
     * Incremented when a value that affects OCR/translation changes, so cached
     * pages are translated again.
     */
    var revision by mutableIntStateOf(0)
        private set

    private var savedSignature: String =
        prefs.getString("translation_signature", null) ?: translationSignature()

    fun groupingConfig(): BubbleGroupingConfig = BubbleGroupingConfig(
        enabled = mergeBubbleColumns,
        maxColumnGapRatio = maxColumnGapRatio,
        minColumnOverlap = minColumnOverlap,
        borderDarkRatio = borderDarkRatio,
    )

    fun translationSignature(): String = buildString {
        append(engine)
        append('|').append(sourceLang)
        append('|').append(targetLang)
        append('|').append(geminiModel)
        append('|').append(geminiSystemPrompt.hashCode())
        append('|').append(geminiApiKeysRaw.hashCode())
        append('|').append(if (geminiVision) 1 else 0)
        append('|').append(if (skipOcr) 1 else 0)
        // Bumped when the vision prompt or the way its boxes are read changes,
        // so pages cached by an older build are translated again.
        append("|v4")
        append('|').append(if (mergeBubbleColumns) 1 else 0)
        append('|').append((maxColumnGapRatio * 1000).toInt())
        append('|').append((minColumnOverlap * 1000).toInt())
        append('|').append((borderDarkRatio * 1000).toInt())
    }

    fun resetDetectionDefaults() {
        mergeBubbleColumns = true
        maxColumnGapRatio = 0.35f
        minColumnOverlap = 0.55f
        borderDarkRatio = 0.40f
        fitTextInsideBubble = true
        minFontSizeSp = 6.5f
        standardFontSizeSp = 12f
        maxFontSizeSp = 16f
        maxHeightExpansion = 1.6f
        horizontalExpansion = 1.20f
        collisionGap = 12f
        collisionIterations = 40
        maxCollisionShiftRatio = 0.30f
    }

    fun getGeminiApiKeys(): List<String> = geminiApiKeysRaw
        .split('\n', ',', ';')
        .map { it.trim() }
        .filter { it.isNotBlank() }

    fun save() {
        val signature = translationSignature()
        val translationChanged = signature != savedSignature

        prefs.edit()
            .putString("engine", engine)
            .putString("gemini_api_keys", geminiApiKeysRaw)
            .putString("gemini_model", geminiModel)
            .putString("source_lang", sourceLang)
            .putString("target_lang", targetLang)
            .putString("gemini_system_prompt", geminiSystemPrompt)
            .putString("thinking_level", thinkingLevel)
            .putString("bubble_style", bubbleStyle)
            .putFloat("outline_width", outlineWidth)
            .putBoolean("gemini_vision", geminiVision)
            .putBoolean("skip_ocr", skipOcr)
            .putString("bubble_orientation", bubbleOrientation)
            .putString("bubble_shape", bubbleShape)
            .putString("bubble_padding", bubblePadding)
            .putBoolean("exact_placement", exactPlacement)
            .putBoolean("uppercase_scanlation", uppercaseScanlation)
            .putBoolean("prevent_collisions", preventCollisions)
            .putFloat("collision_gap", collisionGap)
            .putInt("collision_iterations", collisionIterations)
            .putFloat("max_collision_shift_ratio", maxCollisionShiftRatio)
            .putBoolean("is_manipulation_mode", isManipulationMode)
            .putFloat("border_thickness", borderThickness)
            .putBoolean("merge_bubble_columns", mergeBubbleColumns)
            .putFloat("max_column_gap_ratio", maxColumnGapRatio)
            .putFloat("min_column_overlap", minColumnOverlap)
            .putFloat("border_dark_ratio", borderDarkRatio)
            .putBoolean("fit_text_inside_bubble", fitTextInsideBubble)
            .putFloat("standard_font_size_sp", standardFontSizeSp)
            .putFloat("min_font_size_sp", minFontSizeSp)
            .putFloat("max_font_size_sp", maxFontSizeSp)
            .putFloat("max_height_expansion", maxHeightExpansion)
            .putString("translation_signature", signature)
            .putFloat("global_bubble_scale", globalBubbleScale)
            .putFloat("horizontal_expansion", horizontalExpansion)
            .putFloat("global_font_scale", globalFontScale)
            .putFloat("bubble_opacity", bubbleOpacity)
            .putBoolean("dynamic_bubble_size", dynamicBubbleSize)
            .putBoolean("show_sfx", showSfx)
            .putBoolean("auto_translate", autoTranslate)
            .putInt("batch_size", batchSize)
            .apply()

        savedSignature = signature
        if (translationChanged) {
            revision++
        }
    }

    companion object {
        const val ENGINE_GOOGLE = "google"
        const val ENGINE_GEMINI = "gemini"
        const val DEFAULT_GEMINI_MODEL = "gemini-flash-lite-latest"

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
            |You are the translator and letterer of a manga page. The user turn carries the page image; read it and translate its dialogue from {lang input} into {lang output}.
            |
            |TRANSLATION
            |- Write natural, colloquial {lang output} the way a published scanlation does, never literal textbook prose. Balloons are small, so keep every line short.
            |- Read the whole panel before translating. Japanese drops pronouns: decide first or third person from what the panel shows and from the speech level, and never switch person in the middle of a conversation.
            |- Match the gender of the character who is speaking, as shown in the panel: feminine or masculine pronouns and agreement.
            |- Futanari characters always take feminine pronouns and feminine agreement.
            |- Keep -san, -kun, -chan, -sempai or -sensei only when the scene depends on the honorific.
            |- Adult scenes: translate the intent, not the dictionary meaning. In a sexual context いく / "Ya voy" is "Me vengo", 気持ちいい is "qué rico", だめ is "no... me voy a venir". Keep the intensity of the original.
            |- Onomatopoeia drawn as stylised letters is not dialogue: give a short {lang output} sound word and mark that entry as "sfx". Those are lettered horizontally on a single line at the standard size, so never wrap them in long sentences.
            |- Never translate signs, posters, shop fronts, book spines, credits, watermarks, page numbers, logos, on-screen text or background artwork.
            |- If a numbered box or a balloon has no readable dialogue (empty balloon, only a sound effect, only artwork, garbled OCR), answer exactly "REJECT" for it. Never invent text for it.
            |- Do not add notes, romanisation or explanations.
            |
            |LAYOUT
            |- Rectangles use normalised coordinates 0-1000 over the whole page.
            |- Cover the text only: follow the lines of dialogue, not the balloon outline and not the character. The app centres the translation inside that rectangle and grows it up and down when the {lang output} text is longer, so centre the rectangle on the original text.
        """.trimMargin()

        /** ML Kit only ships the Japanese recognizer in this build. */
        val SOURCE_LANGUAGES = listOf(
            "ja" to "Japonés (日本語)",
        )

        val TARGET_LANGUAGES = listOf(
            "es" to "Español (ES)",
            "en" to "Inglés (EN)",
            "pt" to "Portugués (PT)",
            "fr" to "Francés (FR)",
            "de" to "Alemán (DE)",
            "it" to "Italiano (IT)",
            "ru" to "Ruso (RU)",
        )

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
