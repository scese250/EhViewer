package com.hippo.ehviewer.translator.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hippo.ehviewer.translator.PageTranslation
import com.hippo.ehviewer.translator.PageTranslator
import com.hippo.ehviewer.translator.TranslationStatus
import com.hippo.ehviewer.translator.TranslatorSettings
import kotlinx.coroutines.launch

private const val SECTION_LANGUAGES = "languages"
private const val SECTION_AI = "ai"
private const val SECTION_STYLE = "style"
private const val SECTION_FIT = "fit"
private const val SECTION_DETECTION = "detection"
private const val SECTION_PRELOAD = "preload"

/**
 * Compact translator dialog: the log, the two translate actions and the engine
 * are always on screen; everything else lives in one category that opens at a
 * time, so the sheet does not become a wall of sliders.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var isSourceExpanded by remember { mutableStateOf(false) }
    var isTargetExpanded by remember { mutableStateOf(false) }
    var openSection by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val keys = settings.getGeminiApiKeys()
    val sourceName = TranslatorSettings.SOURCE_LANGUAGES.find { it.first == settings.sourceLang }?.second
        ?: settings.sourceLang
    val targetName = TranslatorSettings.TARGET_LANGUAGES.find { it.first == settings.targetLang }?.second
        ?: settings.targetLang

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Ajustes del Traductor",
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onTranslateCurrent,
                        enabled = !status.isBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (status.isBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                text = when (status) {
                                    is TranslationStatus.Failed -> "Reintentar"
                                    TranslationStatus.Done -> "Traducir de nuevo"
                                    else -> "Traducir"
                                },
                                maxLines = 1,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { onRetranslateNext(retranslateCount) },
                        enabled = !status.isBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Siguientes $retranslateCount", maxLines = 1)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // Motor: la única elección que cambia todo lo demás.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Motor",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (settings.engine == TranslatorSettings.ENGINE_GEMINI) {
                        Text(
                            text = if (keys.isEmpty()) "sin claves" else "${keys.size} claves",
                            fontSize = 11.sp,
                            color = if (keys.isEmpty()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = settings.engine == TranslatorSettings.ENGINE_GOOGLE,
                        onClick = { settings.engine = TranslatorSettings.ENGINE_GOOGLE },
                        label = { Text("Google", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = settings.engine == TranslatorSettings.ENGINE_GEMINI,
                        onClick = { settings.engine = TranslatorSettings.ENGINE_GEMINI },
                        label = { Text("Gemini IA", fontSize = 11.sp) },
                    )
                }

                if (settings.engine == TranslatorSettings.ENGINE_GEMINI && keys.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Sin claves se traduce con Google.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { openSection = SECTION_AI }) {
                            Text("Añadir", fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "$sourceName → $targetName",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Categorías: solo una abierta a la vez.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CategoryChip("Idiomas", openSection == SECTION_LANGUAGES) {
                        openSection = nextSection(openSection, SECTION_LANGUAGES)
                    }
                    CategoryChip("IA", openSection == SECTION_AI) {
                        openSection = nextSection(openSection, SECTION_AI)
                    }
                    CategoryChip("Estilo", openSection == SECTION_STYLE) {
                        openSection = nextSection(openSection, SECTION_STYLE)
                    }
                    CategoryChip("Ajuste", openSection == SECTION_FIT) {
                        openSection = nextSection(openSection, SECTION_FIT)
                    }
                    CategoryChip("Detección", openSection == SECTION_DETECTION) {
                        openSection = nextSection(openSection, SECTION_DETECTION)
                    }
                    CategoryChip("Lote", openSection == SECTION_PRELOAD) {
                        openSection = nextSection(openSection, SECTION_PRELOAD)
                    }
                }

                AnimatedVisibility(visible = openSection == SECTION_LANGUAGES) {
                    Column {
                        SectionTitle("Idiomas de traducción")

                        ExposedDropdownMenuBox(
                            expanded = isSourceExpanded,
                            onExpandedChange = { isSourceExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = sourceName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Idioma de entrada (Manga)") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isSourceExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                            )
                            ExposedDropdownMenu(
                                expanded = isSourceExpanded,
                                onDismissRequest = { isSourceExpanded = false },
                            ) {
                                TranslatorSettings.SOURCE_LANGUAGES.forEach { (code, name) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            settings.sourceLang = code
                                            isSourceExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        ExposedDropdownMenuBox(
                            expanded = isTargetExpanded,
                            onExpandedChange = { isTargetExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = targetName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Idioma de salida (Traducción)") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isTargetExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                            )
                            ExposedDropdownMenu(
                                expanded = isTargetExpanded,
                                onDismissRequest = { isTargetExpanded = false },
                            ) {
                                TranslatorSettings.TARGET_LANGUAGES.forEach { (code, name) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            settings.targetLang = code
                                            isTargetExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        Text(
                            text = "Aplica tanto a Google Translate como a Gemini IA. Idioma de entrada por defecto: Japonés.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                AnimatedVisibility(visible = openSection == SECTION_AI) {
                    Column {
                        SectionTitle("Gemini IA")

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
                                        text = "Claves de API:",
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        text = "${keys.size} detectadas",
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
                                    label = { Text("Pega tus claves (una por línea)") },
                                    placeholder = { Text("AIzaSy...\nAIzaSy...") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    maxLines = 4,
                                )

                                Text(
                                    text = "💡 Varias claves rotan solas cuando una alcanza la cuota (429).",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = "Modelo Gemini:",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodySmall,
                                )

                                OutlinedTextField(
                                    value = settings.geminiModel,
                                    onValueChange = { settings.geminiModel = it.trim() },
                                    label = { Text("ID del Modelo") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    FilterChip(
                                        selected = settings.geminiModel == "gemini-flash-lite-latest",
                                        onClick = { settings.geminiModel = "gemini-flash-lite-latest" },
                                        label = { Text("flash-lite-latest", fontSize = 11.sp) },
                                    )
                                    FilterChip(
                                        selected = settings.geminiModel == "gemini-3.5-flash-lite",
                                        onClick = { settings.geminiModel = "gemini-3.5-flash-lite" },
                                        label = { Text("3.5-flash-lite", fontSize = 11.sp) },
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Plantilla de Prompt IA:",
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    TextButton(
                                        onClick = { settings.geminiPromptTemplate = TranslatorSettings.DEFAULT_PROMPT_TEMPLATE },
                                    ) {
                                        Text("Restablecer", fontSize = 11.sp)
                                    }
                                }

                                OutlinedTextField(
                                    value = settings.geminiPromptTemplate,
                                    onValueChange = { settings.geminiPromptTemplate = it },
                                    label = { Text("Prompt con comodines") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    maxLines = 4,
                                )

                                Text(
                                    text = "💡 Comodines: {lang input} y {lang output}.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Instrucciones del sistema:",
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    TextButton(
                                        onClick = { settings.geminiSystemPrompt = TranslatorSettings.DEFAULT_SYSTEM_PROMPT },
                                    ) {
                                        Text("Restablecer", fontSize = 11.sp)
                                    }
                                }

                                OutlinedTextField(
                                    value = settings.geminiSystemPrompt,
                                    onValueChange = { settings.geminiSystemPrompt = it },
                                    label = { Text("Reglas del traductor (system prompt)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 5,
                                    maxLines = 14,
                                )

                                Text(
                                    text = "💡 Se envían como system prompt; la imagen va como turno del usuario. " +
                                        "Aquí viven los pronombres y el género según el panel, futanari femenino, " +
                                        "los términos H (\"Ya voy\" → \"Me vengo\"), no traducir carteles y el REJECT.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                Spacer(modifier = Modifier.height(10.dp))
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(10.dp))

                                SettingSwitch(
                                    title = "Lectura visual de la página",
                                    subtitle = "La IA lee el texto real dentro de la cuadrícula, ajusta cada globo al " +
                                        "texto, une globos partidos y dibuja las onomatopeyas de lado.",
                                    checked = settings.geminiVision,
                                    onCheckedChange = {
                                        settings.geminiVision = it
                                        if (!it) settings.skipOcr = false
                                    },
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                SettingSwitch(
                                    title = "Omitir el OCR (solo IA)",
                                    subtitle = "No se ejecuta ML Kit: la IA encuentra y coloca todas las burbujas. " +
                                        "Ahorra el OCR y esquiva sus fallos, pero sin cuadros de respaldo; si la " +
                                        "llamada falla, se hace el OCR y se usa el modo texto.",
                                    checked = settings.skipOcr,
                                    onCheckedChange = {
                                        settings.skipOcr = it
                                        if (it) settings.geminiVision = true
                                    },
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
                                        Text("Probando conexión...")
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Probar Claves de API")
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
                        SectionTitle("Estilo de los globos")

                        SettingChips(
                            label = "Orientación",
                            options = listOf(
                                "Vertical" to TranslatorSettings.ORIENTATION_VERTICAL,
                                "Horizontal" to TranslatorSettings.ORIENTATION_HORIZONTAL,
                                "Auto" to TranslatorSettings.ORIENTATION_AUTO,
                            ),
                            selected = settings.bubbleOrientation,
                            onSelect = { settings.bubbleOrientation = it },
                        )

                        SettingChips(
                            label = "Forma",
                            options = listOf(
                                "Óvalo" to TranslatorSettings.SHAPE_OVAL,
                                "Rectángulo" to TranslatorSettings.SHAPE_ROUNDED,
                                "Sin borde" to TranslatorSettings.SHAPE_BORDERLESS,
                            ),
                            selected = settings.bubbleShape,
                            onSelect = { settings.bubbleShape = it },
                        )

                        SettingChips(
                            label = "Fondo del texto",
                            options = listOf(
                                "Burbuja blanca" to TranslatorSettings.STYLE_FILL,
                                "Solo contorno" to TranslatorSettings.STYLE_OUTLINE,
                            ),
                            selected = settings.bubbleStyle,
                            onSelect = { settings.bubbleStyle = it },
                        )
                        Text(
                            text = if (settings.bubbleStyle == TranslatorSettings.STYLE_OUTLINE) {
                                "Sin burbuja: letras negras con un borde blanco grueso."
                            } else {
                                "Bocadillo blanco clásico con el texto negro encima."
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (settings.bubbleStyle == TranslatorSettings.STYLE_OUTLINE) {
                            SettingSlider(
                                label = "Contorno: ${"%.1f".format(settings.outlineWidth)}dp",
                                value = settings.outlineWidth,
                                valueRange = 1f..8f,
                                onValueChange = { settings.outlineWidth = it },
                            )
                        }

                        SettingChips(
                            label = "Margen",
                            options = listOf(
                                "Compacto" to TranslatorSettings.PADDING_COMPACT,
                                "Normal" to TranslatorSettings.PADDING_NORMAL,
                                "Espacioso" to TranslatorSettings.PADDING_SPACIOUS,
                            ),
                            selected = settings.bubblePadding,
                            onSelect = { settings.bubblePadding = it },
                        )

                        SettingSwitch(
                            title = "Mayúsculas Manga",
                            subtitle = "Texto en MAYÚSCULAS como en las publicaciones oficiales",
                            checked = settings.uppercaseScanlation,
                            onCheckedChange = { settings.uppercaseScanlation = it },
                        )

                        val borderLabel = if (settings.borderThickness <= 0.05f) {
                            "Sin borde"
                        } else {
                            "${"%.1f".format(settings.borderThickness)}dp"
                        }
                        SettingSlider(
                            label = "Borde: $borderLabel",
                            value = settings.borderThickness,
                            valueRange = 0f..3.0f,
                            onValueChange = { settings.borderThickness = it },
                        )
                        SettingSlider(
                            label = "Globo: ${"%.2f".format(settings.globalBubbleScale)}x",
                            value = settings.globalBubbleScale,
                            valueRange = 0.7f..1.8f,
                            onValueChange = { settings.globalBubbleScale = it },
                        )
                        SettingSlider(
                            label = "Fuente: ${"%.2f".format(settings.globalFontScale)}x",
                            value = settings.globalFontScale,
                            valueRange = 0.6f..1.5f,
                            onValueChange = { settings.globalFontScale = it },
                        )
                        SettingSlider(
                            label = "Opacidad: ${(settings.bubbleOpacity * 100).toInt()}%",
                            value = settings.bubbleOpacity,
                            valueRange = 0.5f..1.0f,
                            onValueChange = { settings.bubbleOpacity = it },
                        )
                    }
                }

                AnimatedVisibility(visible = openSection == SECTION_FIT) {
                    Column {
                        SectionTitle("Ajuste del texto")

                        SettingSwitch(
                            title = "Posición exacta de la IA",
                            subtitle = "Usa la caja medida por el modelo tal cual: sin ensanchar, sin escalar y sin " +
                                "anti-choque. El alto crece centrado solo si el texto no cabe.",
                            checked = settings.exactPlacement,
                            onCheckedChange = { settings.exactPlacement = it },
                        )

                        SettingSwitch(
                            title = "Burbuja dinámica",
                            subtitle = "Crece arriba y abajo para que entre todo el texto sin cortarse",
                            checked = settings.dynamicBubbleSize,
                            onCheckedChange = { settings.dynamicBubbleSize = it },
                        )
                        SettingSwitch(
                            title = "Ajustar texto dentro del globo",
                            subtitle = "Reduce la fuente en vez de invadir los globos vecinos",
                            checked = settings.fitTextInsideBubble,
                            onCheckedChange = { settings.fitTextInsideBubble = it },
                        )
                        SettingSwitch(
                            title = "Modo manipulación táctil",
                            subtitle = "Mover con 1 dedo, pellizcar con 2 para cambiar el tamaño",
                            checked = settings.isManipulationMode,
                            onCheckedChange = { settings.isManipulationMode = it },
                        )

                        SettingSlider(
                            label = "Fuente mínima: ${"%.1f".format(settings.minFontSizeSp)} sp",
                            value = settings.minFontSizeSp,
                            valueRange = 5f..12f,
                            onValueChange = { settings.minFontSizeSp = it.coerceAtMost(settings.maxFontSizeSp - 1f) },
                        )
                        SettingSlider(
                            label = "Fuente máxima: ${"%.1f".format(settings.maxFontSizeSp)} sp",
                            value = settings.maxFontSizeSp,
                            valueRange = 8f..24f,
                            onValueChange = { settings.maxFontSizeSp = it.coerceAtLeast(settings.minFontSizeSp + 1f) },
                        )
                        SettingSlider(
                            label = "Crecimiento de alto: ${"%.1f".format(settings.maxHeightExpansion)}x",
                            value = settings.maxHeightExpansion,
                            valueRange = 1f..3f,
                            onValueChange = { settings.maxHeightExpansion = it },
                        )
                        SettingSlider(
                            label = "Expansión horizontal: ${"%.2f".format(settings.horizontalExpansion)}x",
                            value = settings.horizontalExpansion,
                            valueRange = 1f..2f,
                            onValueChange = { settings.horizontalExpansion = it },
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        SettingSwitch(
                            title = "Anti-choque de burbujas",
                            subtitle = "Separa globos adyacentes para que no se solapen",
                            checked = settings.preventCollisions,
                            onCheckedChange = { settings.preventCollisions = it },
                        )
                        if (settings.preventCollisions) {
                            SettingSlider(
                                label = "Separación: ${settings.collisionGap.toInt()}dp",
                                value = settings.collisionGap,
                                valueRange = 2f..40f,
                                onValueChange = { settings.collisionGap = it },
                            )
                            SettingSlider(
                                label = "Iteraciones: ${settings.collisionIterations}",
                                value = settings.collisionIterations.toFloat(),
                                valueRange = 10f..80f,
                                onValueChange = { settings.collisionIterations = it.toInt() },
                            )
                            SettingSlider(
                                label = "Desplazamiento: ${(settings.maxCollisionShiftRatio * 100).toInt()}%",
                                value = settings.maxCollisionShiftRatio,
                                valueRange = 0.10f..0.60f,
                                onValueChange = { settings.maxCollisionShiftRatio = it },
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = openSection == SECTION_DETECTION) {
                    Column {
                        SectionTitle("Detección de globos (OCR)")

                        Text(
                            text = "ML Kit devuelve una columna por bloque; estos valores deciden cómo se vuelven a " +
                                "unir las columnas del mismo bocadillo. Al cambiarlos se descarta la caché.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        SettingSwitch(
                            title = "Unir columnas del mismo globo",
                            subtitle = "Evita que un bocadillo vertical salga como dos burbujas superpuestas",
                            checked = settings.mergeBubbleColumns,
                            onCheckedChange = { settings.mergeBubbleColumns = it },
                        )
                        if (settings.mergeBubbleColumns) {
                            SettingSlider(
                                label = "Separación columnas: ${(settings.maxColumnGapRatio * 100).toInt()}%",
                                value = settings.maxColumnGapRatio,
                                valueRange = 0.10f..0.80f,
                                onValueChange = { settings.maxColumnGapRatio = it },
                            )
                            SettingSlider(
                                label = "Solape vertical: ${(settings.minColumnOverlap * 100).toInt()}%",
                                value = settings.minColumnOverlap,
                                valueRange = 0.30f..0.90f,
                                onValueChange = { settings.minColumnOverlap = it },
                            )
                            SettingSlider(
                                label = "Borde oscuro: ${(settings.borderDarkRatio * 100).toInt()}%",
                                value = settings.borderDarkRatio,
                                valueRange = 0.20f..0.70f,
                                onValueChange = { settings.borderDarkRatio = it },
                            )
                        }

                        TextButton(
                            onClick = { settings.resetDetectionDefaults() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Restablecer valores de detección", fontSize = 12.sp)
                        }
                    }
                }

                AnimatedVisibility(visible = openSection == SECTION_PRELOAD) {
                    Column {
                        SectionTitle("Pre-traducción")

                        SettingSwitch(
                            title = "Traducir automáticamente al leer",
                            subtitle = "La página actual se traduce al abrirla y se preparan las siguientes, hasta el " +
                                "límite del preload de EhViewer: $batchLimit página(s).",
                            checked = settings.autoTranslate,
                            onCheckedChange = { settings.autoTranslate = it },
                        )

                        if (settings.autoTranslate && batchLimit > 1) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Lote: ${settings.batchSize}",
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
                            text = "\"Siguientes $retranslateCount\" borra la copia guardada de esas páginas y las " +
                                "vuelve a leer con la configuración actual, sin salir de la galería.",
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
                Text("Listo")
            }
        },
    )
}

private fun nextSection(current: String?, section: String): String? = if (current == section) null else section

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
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
    TranslationStatus.WaitingForPage -> "Esperando la imagen…"
    TranslationStatus.RecognizingText -> "Reconociendo texto (OCR)…"
    TranslationStatus.ReadingPage -> "La IA está leyendo la página…"
    TranslationStatus.TranslatingText -> "Traduciendo texto…"
    else -> "Traduciendo…"
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
        TranslationStatus.Idle -> "Sin traducir"
        TranslationStatus.WaitingForPage -> "Preparando la página"
        TranslationStatus.RecognizingText -> "Reconociendo texto (OCR)"
        TranslationStatus.ReadingPage -> "Leyendo la página con IA"
        TranslationStatus.TranslatingText -> "Traduciendo"
        TranslationStatus.Done -> "Traducción lista"
        is TranslationStatus.Failed -> "Error al traducir"
    }
    val detail = when (status) {
        TranslationStatus.Idle -> "Esta página todavía no se ha traducido."
        TranslationStatus.WaitingForPage -> "Esperando a que la imagen se descargue y decodifique."
        TranslationStatus.RecognizingText -> "ML Kit está localizando los globos de la página."
        TranslationStatus.ReadingPage -> "Gemini está leyendo el texto real dentro de los cuadros numerados."
        TranslationStatus.TranslatingText -> "Enviando los textos al motor de traducción."
        TranslationStatus.Done -> translation?.let { page ->
            if (page.bubbles.isEmpty()) {
                "No se detectó texto en esta página. Prueba otra página o revisa la detección."
            } else {
                buildString {
                    append("${page.bubbles.size} viñetas · ${page.engineName}")
                    val sfxCount = page.bubbles.count { it.isSfx }
                    if (sfxCount > 0) append(" · $sfxCount SFX")
                    if (page.mergedBoxes > 0) append(" · ${page.mergedBoxes} fusionadas")
                    if (page.discardedBoxes > 0) append(" · ${page.discardedBoxes} descartadas")
                    append("\nOCR ${page.ocrDurationMs} ms · Traducción ${page.translateDurationMs} ms")
                }
            }
        } ?: "Página traducida."
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
            verticalAlignment = Alignment.CenterVertically,
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
