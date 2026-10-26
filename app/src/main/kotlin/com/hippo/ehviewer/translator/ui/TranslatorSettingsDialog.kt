package com.hippo.ehviewer.translator.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hippo.ehviewer.translator.PageTranslation
import com.hippo.ehviewer.translator.PageTranslator
import com.hippo.ehviewer.translator.TranslationCache
import com.hippo.ehviewer.translator.TranslationStatus
import com.hippo.ehviewer.translator.TranslatorSettings
import kotlinx.coroutines.launch

private const val SECTION_LANGUAGES = "languages"
private const val SECTION_AI = "ai"
private const val SECTION_STYLE = "style"
private const val SECTION_FIT = "fit"
private const val SECTION_PRELOAD = "preload"
private const val SECTION_CACHE = "cache"

/**
 * Compact translator dialog: the log and the two translate actions are always
 * on screen; everything else lives in one category that opens at a time, so
 * the sheet does not become a wall of sliders.
 */
@Composable
fun TranslatorSettingsDialog(
    settings: TranslatorSettings,
    maxBatchSize: Int,
    status: TranslationStatus,
    translation: PageTranslation?,
    onTranslateCurrent: () -> Unit,
    onRetranslateNext: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val batchLimit = maxBatchSize.coerceAtLeast(1)
    val retranslateCount = minOf(settings.batchSize, batchLimit).coerceAtLeast(1)
    LaunchedEffect(batchLimit) {
        if (settings.batchSize > batchLimit) settings.batchSize = batchLimit
    }
    var isTestingKey by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTestSuccess by remember { mutableStateOf(false) }
    var openSection by remember { mutableStateOf<String?>(null) }
    var cacheStamp by remember { mutableIntStateOf(0) }
    var confirmClearCache by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keys = settings.getGeminiApiKeys()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Translation settings",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                // Log de la traducción de esta página.
                TranslationStatusCard(status = status, translation = translation)

                Spacer(modifier = Modifier.height(8.dp))

                // Both buttons hug their label: the old fixed halves left a
                // blank gap after "Translate". When they do not fit side by
                // side, the second one moves to the next line.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onTranslateCurrent,
                        enabled = !status.isBusy,
                    ) {
                        if (status.isBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                text = when (status) {
                                    is TranslationStatus.Failed -> "Retry"
                                    TranslationStatus.Done -> "Translate again"
                                    else -> "Translate page"
                                },
                                maxLines = 1,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { onRetranslateNext(retranslateCount) },
                        enabled = !status.isBusy,
                    ) {
                        Text(
                            text = "Redo translation next $retranslateCount pages",
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Pausa de la traducción automática: vive fuera de las
                // categorías porque se usa a mitad de lectura.
                SettingSwitch(
                    title = if (settings.autoTranslate) "Auto-translate: on" else "Auto-translate: paused",
                    subtitle = if (settings.autoTranslate) {
                        "Translates the current page and the next ones while you read. Tap to pause."
                    } else {
                        "Nothing is translated on its own; use \"Translate page\" when you want it. Tap to resume."
                    },
                    checked = settings.autoTranslate,
                    onCheckedChange = { settings.autoTranslate = it },
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "${settings.sourceLang} → ${settings.targetLang}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // The model is the only engine now, so an empty key list is the
                // one thing that stops the page from being translated.
                if (keys.isEmpty()) {
                    TextButton(onClick = { openSection = SECTION_AI }) {
                        Text(
                            text = "No API key yet, add one in AI",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Categorías: solo una abierta a la vez.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CategoryChip("Languages", openSection == SECTION_LANGUAGES) {
                        openSection = nextSection(openSection, SECTION_LANGUAGES)
                    }
                    CategoryChip("IA", openSection == SECTION_AI) {
                        openSection = nextSection(openSection, SECTION_AI)
                    }
                    CategoryChip("Style", openSection == SECTION_STYLE) {
                        openSection = nextSection(openSection, SECTION_STYLE)
                    }
                    CategoryChip("Fit", openSection == SECTION_FIT) {
                        openSection = nextSection(openSection, SECTION_FIT)
                    }
                    CategoryChip("Batch", openSection == SECTION_PRELOAD) {
                        openSection = nextSection(openSection, SECTION_PRELOAD)
                    }
                    CategoryChip("Cache", openSection == SECTION_CACHE) {
                        openSection = nextSection(openSection, SECTION_CACHE)
                        confirmClearCache = false
                        // Re-read the size every time the group is opened.
                        cacheStamp++
                    }
                }

                AnimatedVisibility(visible = openSection == SECTION_LANGUAGES) {
                    Column {
                        SectionTitle("Translation languages")

                        OutlinedTextField(
                            value = settings.sourceLang,
                            onValueChange = { settings.sourceLang = it },
                            label = { Text("Language input") },
                            placeholder = { Text("Japanese") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = settings.targetLang,
                            onValueChange = { settings.targetLang = it },
                            label = { Text("Language output") },
                            placeholder = { Text("English") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }

                AnimatedVisibility(visible = openSection == SECTION_AI) {
                    Column {
                        SectionTitle("Gemini AI")

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "API keys:",
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        text = "${keys.size} detected",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (keys.isNotEmpty()) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                OutlinedTextField(
                                    value = settings.geminiApiKeysRaw,
                                    onValueChange = {
                                        settings.geminiApiKeysRaw = it
                                        testResult = null
                                    },
                                    label = { Text("Paste your keys (one per line)") },
                                    placeholder = { Text("AIzaSy...\nAIzaSy...") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    maxLines = 4,
                                )

                                Text(
                                    text = "💡 Each key translates its own page, so more keys mean more pages translated at the same time.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = "Gemini model:",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodySmall,
                                )

                                OutlinedTextField(
                                    value = settings.geminiModel,
                                    onValueChange = { settings.geminiModel = it.trim() },
                                    label = { Text("Model ID") },
                                    placeholder = { Text(TranslatorSettings.DEFAULT_GEMINI_MODEL) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { settings.addGeminiModel(settings.geminiModel) },
                                            enabled = settings.geminiModel.isNotBlank(),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = "Add to the quick selector",
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    },
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                if (settings.geminiModels.isEmpty()) {
                                    Text(
                                        text = "Quick selector is empty: type a model ID and tap + to save it.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        settings.geminiModels.forEach { model ->
                                            FilterChip(
                                                selected = settings.geminiModel == model,
                                                onClick = { settings.geminiModel = model },
                                                label = { Text(model, fontSize = 11.sp, maxLines = 1) },
                                                trailingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = "Remove $model from the quick selector",
                                                        modifier = Modifier
                                                            .size(18.dp)
                                                            .clickable { settings.removeGeminiModel(model) },
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                SettingChips(
                                    label = "Thinking",
                                    options = TranslatorSettings.THINKING_LEVELS,
                                    selected = settings.thinkingLevel,
                                    onSelect = { settings.thinkingLevel = it },
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                PromptEditor(
                                    title = "System instructions",
                                    value = settings.geminiSystemPrompt,
                                    onValueChange = { settings.geminiSystemPrompt = it },
                                    defaultValue = TranslatorSettings.DEFAULT_SYSTEM_PROMPT,
                                    fieldLabel = "Translator rules (system prompt)",
                                    hint = "💡 Sent as the system prompt; the image goes as the user turn. " +
                                        "This is where pronouns and per-panel gender, leaving signs untranslated " +
                                        "and the REJECT live.",
                                    minLines = 5,
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                PromptEditor(
                                    title = "User instructions",
                                    value = settings.geminiUserPrompt,
                                    onValueChange = { settings.geminiUserPrompt = it },
                                    defaultValue = TranslatorSettings.DEFAULT_USER_PROMPT,
                                    fieldLabel = "Additional instructions (user prompt)",
                                    placeholder = "e.g. Keep the -san and -kun honorifics; never use formal speech.",
                                    hint = "💡 Sent in the user turn, along with the page and the task. " +
                                        "Use it for one-off tweaks (tone, names, terms) without touching the system rules.",
                                    minLines = 3,
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            isTestingKey = true
                                            testResult = null
                                            val res = PageTranslator.testGeminiConnection(
                                                apiKeys = settings.getGeminiApiKeys(),
                                                model = settings.geminiModel,
                                            )
                                            isTestingKey = false
                                            res.onSuccess {
                                                isTestSuccess = true
                                                testResult = it
                                            }.onFailure {
                                                isTestSuccess = false
                                                testResult = it.message
                                            }
                                        }
                                    },
                                    enabled = !isTestingKey && keys.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    if (isTestingKey) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Testing connection…")
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Test API keys")
                                    }
                                }

                                testResult?.let { msg ->
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = msg,
                                        fontSize = 11.sp,
                                        color = if (isTestSuccess) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = openSection == SECTION_STYLE) {
                    Column {
                        SectionTitle("Balloon style")

                        SettingChips(
                            label = "Orientation",
                            options = listOf(
                                "Vertical" to TranslatorSettings.ORIENTATION_VERTICAL,
                                "Horizontal" to TranslatorSettings.ORIENTATION_HORIZONTAL,
                                "Auto" to TranslatorSettings.ORIENTATION_AUTO,
                            ),
                            selected = settings.bubbleOrientation,
                            onSelect = { settings.bubbleOrientation = it },
                        )

                        SettingChips(
                            label = "Shape",
                            options = listOf(
                                "Oval" to TranslatorSettings.SHAPE_OVAL,
                                "Rectangle" to TranslatorSettings.SHAPE_ROUNDED,
                                "Borderless" to TranslatorSettings.SHAPE_BORDERLESS,
                            ),
                            selected = settings.bubbleShape,
                            onSelect = { settings.bubbleShape = it },
                        )

                        SettingChips(
                            label = "Text background",
                            options = listOf(
                                "White balloon" to TranslatorSettings.STYLE_FILL,
                                "Outline only" to TranslatorSettings.STYLE_OUTLINE,
                            ),
                            selected = settings.bubbleStyle,
                            onSelect = { settings.bubbleStyle = it },
                        )
                        Text(
                            text = if (settings.bubbleStyle == TranslatorSettings.STYLE_OUTLINE) {
                                "No balloon: black letters with a thick white outline."
                            } else {
                                "Classic white balloon with the text in black on top."
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (settings.bubbleStyle == TranslatorSettings.STYLE_OUTLINE) {
                            SettingSlider(
                                label = "Outline: ${"%.1f".format(settings.outlineWidth)}dp",
                                value = settings.outlineWidth,
                                valueRange = 1f..8f,
                                onValueChange = { settings.outlineWidth = it },
                            )
                        }

                        SettingChips(
                            label = "Padding",
                            options = listOf(
                                "Compact" to TranslatorSettings.PADDING_COMPACT,
                                "Normal" to TranslatorSettings.PADDING_NORMAL,
                                "Spacious" to TranslatorSettings.PADDING_SPACIOUS,
                            ),
                            selected = settings.bubblePadding,
                            onSelect = { settings.bubblePadding = it },
                        )

                        SettingSwitch(
                            title = "Manga uppercase",
                            subtitle = "ALL-CAPS text, like official print releases",
                            checked = settings.uppercaseScanlation,
                            onCheckedChange = { settings.uppercaseScanlation = it },
                        )

                        SettingSwitch(
                            title = "Show sound effects (SFX)",
                            subtitle = "The AI still returns them and they stay saved; turning this off only " +
                                "hides them, without translating the page again.",
                            checked = settings.showSfx,
                            onCheckedChange = { settings.showSfx = it },
                        )

                        val borderLabel = if (settings.borderThickness <= 0.05f) {
                            "none"
                        } else {
                            "${"%.1f".format(settings.borderThickness)}dp"
                        }
                        SettingSlider(
                            label = "Border: $borderLabel",
                            value = settings.borderThickness,
                            valueRange = 0f..3.0f,
                            onValueChange = { settings.borderThickness = it },
                        )
                        SettingSlider(
                            label = "Balloon: ${"%.2f".format(settings.globalBubbleScale)}x",
                            value = settings.globalBubbleScale,
                            valueRange = 0.7f..1.8f,
                            onValueChange = { settings.globalBubbleScale = it },
                        )
                        SettingSlider(
                            label = "Opacity: ${(settings.bubbleOpacity * 100).toInt()}%",
                            value = settings.bubbleOpacity,
                            valueRange = 0.5f..1.0f,
                            onValueChange = { settings.bubbleOpacity = it },
                        )
                    }
                }

                AnimatedVisibility(visible = openSection == SECTION_FIT) {
                    Column {
                        SectionTitle("Text fit")

                        SettingSlider(
                            label = "Font: ${"%.1f".format(settings.standardFontSizeSp)} sp",
                            value = settings.standardFontSizeSp,
                            valueRange = 6f..24f,
                            onValueChange = { settings.standardFontSizeSp = it },
                        )
                        Text(
                            text = "A single size for every balloon. The AI only measures where the text goes; " +
                                "if it does not fit, the box widens and the letters shrink instead of being cut.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        SettingSwitch(
                            title = "Touch manipulation mode",
                            subtitle = "Drag with one finger to move the text. Its size is fixed by the settings above.",
                            checked = settings.isManipulationMode,
                            onCheckedChange = { settings.isManipulationMode = it },
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        SettingSwitch(
                            title = "Anti-overlap",
                            subtitle = "Pushes apart texts that land on top of each other, including the ones measured by the AI",
                            checked = settings.preventCollisions,
                            onCheckedChange = { settings.preventCollisions = it },
                        )
                        if (settings.preventCollisions) {
                            SettingSlider(
                                label = if (settings.collisionGap < 0.5f) {
                                    "Gap: touching"
                                } else {
                                    "Gap: ${settings.collisionGap.toInt()}dp"
                                },
                                value = settings.collisionGap,
                                valueRange = 0f..24f,
                                onValueChange = { settings.collisionGap = it },
                            )
                            SettingSlider(
                                label = "Iterations: ${settings.collisionIterations}",
                                value = settings.collisionIterations.toFloat(),
                                valueRange = 10f..80f,
                                onValueChange = { settings.collisionIterations = it.toInt() },
                            )
                            SettingSlider(
                                label = "Shift: ${(settings.maxCollisionShiftRatio * 100).toInt()}%",
                                value = settings.maxCollisionShiftRatio,
                                valueRange = 0.10f..0.60f,
                                onValueChange = { settings.maxCollisionShiftRatio = it },
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = openSection == SECTION_CACHE) {
                    Column {
                        SectionTitle("Translation cache")

                        val stats = remember(cacheStamp) { TranslationCache.stats() }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${stats.pages} pages saved",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = formatBytes(stats.bytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Text(
                            text = "Kept in the app storage and survives restarts, so coming back to a gallery " +
                                "does not translate its pages again. It is cleared only when you change the " +
                                "model, the languages or the instructions (system or user).",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        if (confirmClearCache) {
                            Text(
                                text = "The ${stats.pages} saved pages will be deleted; they will have to be translated again.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        TranslationCache.clearAll()
                                        confirmClearCache = false
                                        cacheStamp++
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Confirm")
                                }
                                OutlinedButton(
                                    onClick = { confirmClearCache = false },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Cancel")
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { confirmClearCache = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Clear cache")
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = openSection == SECTION_PRELOAD) {
                    Column {
                        SectionTitle("Preload")

                        Text(
                            text = if (settings.autoTranslate) {
                                "On (pause and resume it above). It keeps the current page and the next " +
                                    "${settings.batchSize.coerceAtMost(batchLimit)} ready; the batch moves with you " +
                                    "as you change pages. EhViewer preload limit: $batchLimit."
                            } else {
                                "Paused (resume it above): nothing is translated while you read."
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        if (batchLimit > 1) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Batch: ${settings.batchSize}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.width(90.dp),
                                )
                                Slider(
                                    value = settings.batchSize.coerceAtMost(batchLimit).toFloat(),
                                    onValueChange = { settings.batchSize = it.toInt().coerceIn(1, batchLimit) },
                                    valueRange = 1f..batchLimit.toFloat(),
                                    steps = (batchLimit - 2).coerceAtLeast(0),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "\"Redo translation next $retranslateCount pages\" drops the saved copy of " +
                                "those pages and reads them again with the current settings, without leaving the gallery.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
            ) {
                Text("Done")
            }
        },
    )
}

private fun nextSection(current: String?, section: String): String? = if (current == section) null else section

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) = FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(label, fontSize = 12.sp) },
)

@Composable
private fun SectionTitle(title: String) {
    Spacer(modifier = Modifier.height(10.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(10.dp))
    Text(
        text = title,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(modifier = Modifier.height(6.dp))
}

/**
 * Collapsed prompt field: the instructions are long, so they stay folded until
 * the user opens them. "Restablecer" is always reachable from the header.
 */
@Composable
private fun PromptEditor(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    defaultValue: String,
    fieldLabel: String,
    hint: String,
    minLines: Int,
    placeholder: String = "",
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (expanded) 180f else 0f),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "$title:",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        TextButton(onClick = { onValueChange(defaultValue) }) {
            Text("Reset", fontSize = 11.sp)
        }
    }
    AnimatedVisibility(visible = expanded) {
        Column {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(fieldLabel) },
                placeholder = { Text(placeholder) },
                modifier = Modifier.fillMaxWidth(),
                minLines = minLines,
                maxLines = 14,
            )
            Text(
                text = hint,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingChips(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Text(
        text = "$label:",
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(modifier = Modifier.height(4.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (text, value) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(text, fontSize = 11.sp) },
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

private fun TranslationStatus.busyLabel(): String = when (this) {
    TranslationStatus.WaitingForPage -> "Waiting for the image…"
    TranslationStatus.RecognizingText -> "Recognizing text…"
    TranslationStatus.ReadingPage -> "The AI is reading the page…"
    TranslationStatus.TranslatingText -> "Translating text…"
    else -> "Translating…"
}

@Composable
private fun TranslationStatusCard(status: TranslationStatus, translation: PageTranslation?) {
    val container = when (status) {
        is TranslationStatus.Failed -> MaterialTheme.colorScheme.errorContainer
        TranslationStatus.Done -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (status) {
        is TranslationStatus.Failed -> MaterialTheme.colorScheme.onErrorContainer
        TranslationStatus.Done -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val title = when (status) {
        TranslationStatus.Idle -> "Not translated"
        TranslationStatus.WaitingForPage -> "Preparing the page"
        TranslationStatus.RecognizingText -> "Recognizing text"
        TranslationStatus.ReadingPage -> "AI is reading the page"
        TranslationStatus.TranslatingText -> "Translating"
        TranslationStatus.Done -> "Translation ready"
        is TranslationStatus.Failed -> "Translation failed"
    }
    val detail = when (status) {
        TranslationStatus.Idle -> "This page has not been translated yet."
        TranslationStatus.WaitingForPage -> "Waiting for the image to download and decode."
        TranslationStatus.RecognizingText -> "The AI is locating the balloons on the page."
        TranslationStatus.ReadingPage -> "Gemini is reading the real text inside the numbered boxes."
        TranslationStatus.TranslatingText -> "Sending the texts to Gemini."
        TranslationStatus.Done -> translation?.let { page ->
            if (page.bubbles.isEmpty()) {
                "The AI found no text on this page. Nothing was saved for it, so it is tried again " +
                    "the next time you open it."
            } else {
                buildString {
                    append("${page.bubbles.size} balloons · ${page.engineName}")
                    val sfxCount = page.bubbles.count { it.isSfx }
                    if (sfxCount > 0) append(" · $sfxCount SFX")
                    if (page.mergedBoxes > 0) append(" · ${page.mergedBoxes} merged")
                    if (page.discardedBoxes > 0) append(" · ${page.discardedBoxes} discarded")
                    append("\nTranslation ${page.translateDurationMs} ms")
                }
            }
        } ?: "Page translated."
        is TranslationStatus.Failed -> status.message
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (status) {
                TranslationStatus.WaitingForPage,
                TranslationStatus.RecognizingText,
                TranslationStatus.ReadingPage,
                TranslationStatus.TranslatingText,
                -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                TranslationStatus.Done -> Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                is TranslationStatus.Failed -> Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                TranslationStatus.Idle -> Icon(
                    imageVector = Icons.Filled.Translate,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                Text(
                    text = detail,
                    fontSize = 11.sp,
                )
                // The API reply stays here verbatim, also after the banner was
                // closed while reading: this is the only place it is kept.
                val rawResponse = (status as? TranslationStatus.Failed)
                    ?.rawResponse
                    ?.takeIf { it.isNotBlank() }
                if (rawResponse != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = rawResponse,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(178.dp),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.weight(1f),
        )
    }
}
