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
    var globalBubbleScale by mutableFloatStateOf(prefs.getFloat("global_bubble_scale", 1.25f))
    var horizontalExpansion by mutableFloatStateOf(prefs.getFloat("horizontal_expansion", 2.0f))
    var globalFontScale by mutableFloatStateOf(prefs.getFloat("global_font_scale", 1.0f))
    var bubbleOpacity by mutableFloatStateOf(prefs.getFloat("bubble_opacity", 0.95f))
    var autoPretranslate by mutableStateOf(prefs.getBoolean("auto_pretranslate", true))
    var batchSize by mutableIntStateOf(prefs.getInt("batch_size", 3))

    fun getGeminiApiKeys(): List<String> {
        return geminiApiKeysRaw
            .split('\n', ',', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    fun save() {
        prefs.edit()
            .putString("engine", engine)
            .putString("gemini_api_keys", geminiApiKeysRaw)
            .putString("gemini_model", geminiModel)
            .putFloat("global_bubble_scale", globalBubbleScale)
            .putFloat("horizontal_expansion", horizontalExpansion)
            .putFloat("global_font_scale", globalFontScale)
            .putFloat("bubble_opacity", bubbleOpacity)
            .putBoolean("auto_pretranslate", autoPretranslate)
            .putInt("batch_size", batchSize)
            .apply()
    }

    companion object {
        const val ENGINE_GOOGLE = "google"
        const val ENGINE_GEMINI = "gemini"
        const val DEFAULT_GEMINI_MODEL = "gemini-flash-lite-latest"
    }
}
