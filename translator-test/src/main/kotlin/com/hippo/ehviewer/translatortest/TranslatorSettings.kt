package com.hippo.ehviewer.translatortest

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
    var autoPretranslate by mutableStateOf(prefs.getBoolean("auto_pretranslate", true))
    var batchSize by mutableIntStateOf(prefs.getInt("batch_size", 3))
    var geminiPromptTemplate by mutableStateOf(
        prefs.getString("gemini_prompt_template", DEFAULT_PROMPT_TEMPLATE) ?: DEFAULT_PROMPT_TEMPLATE,
    )
    var bubbleOrientation by mutableStateOf(
        prefs.getString("bubble_orientation", ORIENTATION_VERTICAL) ?: ORIENTATION_VERTICAL,
    )
    var bubbleShape by mutableStateOf(
        prefs.getString("bubble_shape", SHAPE_OVAL) ?: SHAPE_OVAL,
    )
    var bubblePadding by mutableStateOf(
        prefs.getString("bubble_padding", PADDING_COMPACT) ?: PADDING_COMPACT,
    )
    var uppercaseScanlation by mutableStateOf(prefs.getBoolean("uppercase_scanlation", true))
    var preventCollisions by mutableStateOf(prefs.getBoolean("prevent_collisions", true))
    var collisionGap by mutableFloatStateOf(prefs.getFloat("collision_gap", 12f))
    var borderThickness by mutableFloatStateOf(prefs.getFloat("border_thickness", 1.2f))
    var isManipulationMode by mutableStateOf(prefs.getBoolean("is_manipulation_mode", false))

    fun getGeminiApiKeys(): List<String> = geminiApiKeysRaw
        .split('\n', ',', ';')
        .map { it.trim() }
        .filter { it.isNotBlank() }

    fun save() {
        prefs.edit()
            .putString("engine", engine)
            .putString("gemini_api_keys", geminiApiKeysRaw)
            .putString("gemini_model", geminiModel)
            .putString("source_lang", sourceLang)
            .putString("target_lang", targetLang)
            .putString("gemini_prompt_template", geminiPromptTemplate)
            .putString("bubble_orientation", bubbleOrientation)
            .putString("bubble_shape", bubbleShape)
            .putString("bubble_padding", bubblePadding)
            .putBoolean("uppercase_scanlation", uppercaseScanlation)
            .putBoolean("prevent_collisions", preventCollisions)
            .putFloat("collision_gap", collisionGap)
            .putBoolean("is_manipulation_mode", isManipulationMode)
            .putFloat("border_thickness", borderThickness)
            .putFloat("global_bubble_scale", globalBubbleScale)
            .putFloat("horizontal_expansion", horizontalExpansion)
            .putFloat("global_font_scale", globalFontScale)
            .putFloat("bubble_opacity", bubbleOpacity)
            .putBoolean("dynamic_bubble_size", dynamicBubbleSize)
            .putBoolean("auto_pretranslate", autoPretranslate)
            .putInt("batch_size", batchSize)
            .apply()
    }

    companion object {
        const val ENGINE_GOOGLE = "google"
        const val ENGINE_GEMINI = "gemini"
        const val DEFAULT_GEMINI_MODEL = "gemini-flash-lite-latest"

        const val ORIENTATION_VERTICAL = "vertical"
        const val ORIENTATION_HORIZONTAL = "horizontal"
        const val ORIENTATION_AUTO = "auto"

        const val SHAPE_OVAL = "oval"
        const val SHAPE_ROUNDED = "rounded"
        const val SHAPE_BORDERLESS = "borderless"

        const val PADDING_COMPACT = "compact"
        const val PADDING_NORMAL = "normal"
        const val PADDING_SPACIOUS = "spacious"
        const val DEFAULT_PROMPT_TEMPLATE =
            "Translate from {lang input} to {lang output}. Translate the following manga dialogue lines into natural, expressive, and concise {lang output} suitable for speech bubbles. Maintain conversational tone and context between lines on the same page."

        val SOURCE_LANGUAGES = listOf(
            "ja" to "Japonés (日本語)",
            "en" to "Inglés (English)",
            "zh" to "Chino (中文)",
            "ko" to "Coreano (한국어)",
            "es" to "Español (Español)",
            "fr" to "Francés (Français)",
            "de" to "Alemán (Deutsch)",
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
