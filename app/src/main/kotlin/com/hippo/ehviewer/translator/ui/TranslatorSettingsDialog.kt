package com.hippo.ehviewer.translator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.RadioButton
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorSettingsDialog(
    settings: TranslatorSettings,
    maxBatchSize: Int,
    status: TranslationStatus,
    translation: PageTranslation?,
    onTranslateCurrent: () -> Unit,
    onDismiss: () -> Unit,
) {
    val batchLimit = maxBatchSize.coerceAtLeast(1)
    LaunchedEffect(batchLimit) {
        if (settings.batchSize > batchLimit) settings.batchSize = batchLimit
    }
    var isTestingKey by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTestSuccess by remember { mutableStateOf(false) }
    var isSourceExpanded by remember { mutableStateOf(false) }
    var isTargetExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
                // Section 0: state of the current page translation
                TranslationStatusCard(status = status, translation = translation)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onTranslateCurrent,
                    enabled = !status.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (status.isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(status.busyLabel())
                    } else {
                        Text(
                            when (status) {
                                is TranslationStatus.Failed -> "Reintentar traducción"
                                TranslationStatus.Done -> "Traducir de nuevo esta página"
                                else -> "Traducir esta página"
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // Section 0: Idiomas de Entrada y Salida (Sin autodetección)
                Text(
                    text = "Idiomas de Traducción",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Aplica tanto para Google Translate como para Gemini IA. Idioma de entrada por defecto: Japonés.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Source Language
                ExposedDropdownMenuBox(
                    expanded = isSourceExpanded,
                    onExpandedChange = { isSourceExpanded = it },
                ) {
                    val currentSrc = TranslatorSettings.SOURCE_LANGUAGES.find { it.first == settings.sourceLang }?.second
                        ?: settings.sourceLang
                    OutlinedTextField(
                        value = currentSrc,
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

                // Target Language
                ExposedDropdownMenuBox(
                    expanded = isTargetExpanded,
                    onExpandedChange = { isTargetExpanded = it },
                ) {
                    val currentTgt = TranslatorSettings.TARGET_LANGUAGES.find { it.first == settings.targetLang }?.second
                        ?: settings.targetLang
                    OutlinedTextField(
                        value = currentTgt,
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

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Section 1: Engine Selection
                Text(
                    text = "Motor de Traducción",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = settings.engine == TranslatorSettings.ENGINE_GOOGLE,
                        onClick = { settings.engine = TranslatorSettings.ENGINE_GOOGLE },
                    )
                    Text(
                        text = "Google Translate (Gratuito / Rápido)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = settings.engine == TranslatorSettings.ENGINE_GEMINI,
                        onClick = { settings.engine = TranslatorSettings.ENGINE_GEMINI },
                    )
                    Text(
                        text = "Gemini IA (${settings.geminiModel})",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                if (settings.engine == TranslatorSettings.ENGINE_GEMINI) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            val keys = settings.getGeminiApiKeys()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Claves de API Gemini:",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = "${keys.size} detectadas",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (keys.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
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

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "💡 Puedes ingresar varias claves. Rotarán automáticamente si una alcanza la cuota (429).",
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

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "💡 Comodines: {lang input} y {lang output}. Se sustituyen automáticamente por los idiomas seleccionados.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Probar Claves de API")
                                }
                            }

                            testResult?.let { msg ->
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = msg,
                                    fontSize = 11.sp,
                                    color = if (isTestSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Section 2: Estilo de Manga y Globos
                Text(
                    text = "🎨 Estilo de Manga y Globos",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Orientación de globos
                Text(
                    text = "Orientación de los globos:",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilterChip(
                        selected = settings.bubbleOrientation == TranslatorSettings.ORIENTATION_VERTICAL,
                        onClick = { settings.bubbleOrientation = TranslatorSettings.ORIENTATION_VERTICAL },
                        label = { Text("Vertical (Manga)", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = settings.bubbleOrientation == TranslatorSettings.ORIENTATION_HORIZONTAL,
                        onClick = { settings.bubbleOrientation = TranslatorSettings.ORIENTATION_HORIZONTAL },
                        label = { Text("Horizontal", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = settings.bubbleOrientation == TranslatorSettings.ORIENTATION_AUTO,
                        onClick = { settings.bubbleOrientation = TranslatorSettings.ORIENTATION_AUTO },
                        label = { Text("Auto", fontSize = 11.sp) },
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Forma del globo
                Text(
                    text = "Forma del bocadillo:",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilterChip(
                        selected = settings.bubbleShape == TranslatorSettings.SHAPE_OVAL,
                        onClick = { settings.bubbleShape = TranslatorSettings.SHAPE_OVAL },
                        label = { Text("Óvalo Manga", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = settings.bubbleShape == TranslatorSettings.SHAPE_ROUNDED,
                        onClick = { settings.bubbleShape = TranslatorSettings.SHAPE_ROUNDED },
                        label = { Text("Rectángulo Suave", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = settings.bubbleShape == TranslatorSettings.SHAPE_BORDERLESS,
                        onClick = { settings.bubbleShape = TranslatorSettings.SHAPE_BORDERLESS },
                        label = { Text("Sin Borde", fontSize = 11.sp) },
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Margen de relleno
                Text(
                    text = "Margen de relleno del globo:",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilterChip(
                        selected = settings.bubblePadding == TranslatorSettings.PADDING_COMPACT,
                        onClick = { settings.bubblePadding = TranslatorSettings.PADDING_COMPACT },
                        label = { Text("Compacto (Manga)", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = settings.bubblePadding == TranslatorSettings.PADDING_NORMAL,
                        onClick = { settings.bubblePadding = TranslatorSettings.PADDING_NORMAL },
                        label = { Text("Normal", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = settings.bubblePadding == TranslatorSettings.PADDING_SPACIOUS,
                        onClick = { settings.bubblePadding = TranslatorSettings.PADDING_SPACIOUS },
                        label = { Text("Espacioso", fontSize = 11.sp) },
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Scanlation Uppercase
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Mayúsculas Manga (Scanlation)",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Muestra el texto en MAYÚSCULAS como en las publicaciones oficiales",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.uppercaseScanlation,
                        onCheckedChange = { settings.uppercaseScanlation = it },
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Anti-Collision
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Detector Anti-Choque de Burbujas",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Rueda y separa automáticamente globos adyacentes para que no se solapen",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.preventCollisions,
                        onCheckedChange = { settings.preventCollisions = it },
                    )
                }

                if (settings.preventCollisions) {
                    Spacer(modifier = Modifier.height(4.dp))
                    SettingSlider(
                        label = "Margen de separación: ${settings.collisionGap.toInt()}dp",
                        value = settings.collisionGap,
                        valueRange = 2f..40f,
                        onValueChange = { settings.collisionGap = it },
                    )
                    SettingSlider(
                        label = "Iteraciones anti-choque: ${settings.collisionIterations}",
                        value = settings.collisionIterations.toFloat(),
                        valueRange = 10f..80f,
                        onValueChange = { settings.collisionIterations = it.toInt() },
                    )
                    SettingSlider(
                        label = "Desplazamiento máx.: ${(settings.maxCollisionShiftRatio * 100).toInt()}%",
                        value = settings.maxCollisionShiftRatio,
                        valueRange = 0.10f..0.60f,
                        onValueChange = { settings.maxCollisionShiftRatio = it },
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // On-screen touch manipulation
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Modo Manipulación Táctil",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Mover globos con 1 dedo o pellizcar con 2 dedos para cambiar tamaño",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.isManipulationMode,
                        onCheckedChange = { settings.isManipulationMode = it },
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Burbuja Dinámica Automática",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Ajusta la altura para que entre todo el texto sin cortarse",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.dynamicBubbleSize,
                        onCheckedChange = { settings.dynamicBubbleSize = it },
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Border thickness slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val borderLabel = if (settings.borderThickness <= 0.05f) "Sin borde" else "${"%.1f".format(settings.borderThickness)}dp"
                    Text(
                        text = "Borde: $borderLabel",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(115.dp),
                    )
                    Slider(
                        value = settings.borderThickness,
                        onValueChange = { settings.borderThickness = it },
                        valueRange = 0f..3.0f,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Tamaño globo: ${"%.2f".format(settings.globalBubbleScale)}x",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(115.dp),
                    )
                    Slider(
                        value = settings.globalBubbleScale,
                        onValueChange = { settings.globalBubbleScale = it },
                        valueRange = 0.7f..1.8f,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Tamaño fuente: ${"%.2f".format(settings.globalFontScale)}x",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(115.dp),
                    )
                    Slider(
                        value = settings.globalFontScale,
                        onValueChange = { settings.globalFontScale = it },
                        valueRange = 0.6f..1.5f,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Opacidad: ${(settings.bubbleOpacity * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(110.dp),
                    )
                    Slider(
                        value = settings.bubbleOpacity,
                        onValueChange = { settings.bubbleOpacity = it },
                        valueRange = 0.5f..1.0f,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Section 3: Detección de globos (agrupado de columnas OCR)
                Text(
                    text = "🔍 Detección de Globos (OCR)",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                Text(
                    text = "ML Kit devuelve una columna de texto por bloque. Estos valores controlan cómo se vuelven a unir las columnas de un mismo bocadillo y cómo se ajusta el texto dentro de él. Al cerrar el diálogo se borra la caché y se vuelve a traducir la página actual si la traducción automática está activada.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(6.dp))

                SettingSwitch(
                    title = "Unir columnas del mismo globo",
                    subtitle = "Evita que un bocadillo vertical se detecte como dos burbujas superpuestas",
                    checked = settings.mergeBubbleColumns,
                    onCheckedChange = { settings.mergeBubbleColumns = it },
                )

                if (settings.mergeBubbleColumns) {
                    Spacer(modifier = Modifier.height(4.dp))
                    SettingSlider(
                        label = "Separación máx. columnas: ${(settings.maxColumnGapRatio * 100).toInt()}%",
                        value = settings.maxColumnGapRatio,
                        valueRange = 0.10f..0.80f,
                        onValueChange = { settings.maxColumnGapRatio = it },
                    )
                    SettingSlider(
                        label = "Solapamiento mín. vertical: ${(settings.minColumnOverlap * 100).toInt()}%",
                        value = settings.minColumnOverlap,
                        valueRange = 0.30f..0.90f,
                        onValueChange = { settings.minColumnOverlap = it },
                    )
                    SettingSlider(
                        label = "Umbral de borde oscuro: ${(settings.borderDarkRatio * 100).toInt()}%",
                        value = settings.borderDarkRatio,
                        valueRange = 0.20f..0.70f,
                        onValueChange = { settings.borderDarkRatio = it },
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                SettingSwitch(
                    title = "Ajustar texto dentro del globo",
                    subtitle = "Reduce la fuente para que la traducción quepa en el bocadillo original en vez de invadir los vecinos",
                    checked = settings.fitTextInsideBubble,
                    onCheckedChange = { settings.fitTextInsideBubble = it },
                )

                Spacer(modifier = Modifier.height(4.dp))

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
                    label = "Expansión máx. de alto: ${"%.1f".format(settings.maxHeightExpansion)}x",
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

                TextButton(
                    onClick = { settings.resetDetectionDefaults() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Restablecer valores de detección", fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Section 4: Pre-traducción en segundo plano
                Text(
                    text = "Pre-traducción en Segundo Plano",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Traducir automáticamente al leer",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = settings.autoTranslate,
                        onCheckedChange = { settings.autoTranslate = it },
                    )
                }

                Text(
                    text = "La página actual se traduce al abrirla (o al pulsar el botón de arriba) y se preparan las siguientes. El lote nunca supera el preload de EhViewer: $batchLimit página(s).",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (settings.autoTranslate && batchLimit > 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Lote: ${settings.batchSize} páginas",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(110.dp),
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

private fun TranslationStatus.busyLabel(): String = when (this) {
    TranslationStatus.WaitingForPage -> "Esperando la imagen…"
    TranslationStatus.RecognizingText -> "Reconociendo texto (OCR)…"
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
        TranslationStatus.TranslatingText -> "Traduciendo"
        TranslationStatus.Done -> "Traducción lista"
        is TranslationStatus.Failed -> "Error al traducir"
    }
    val detail = when (status) {
        TranslationStatus.Idle -> "Esta página todavía no se ha traducido."
        TranslationStatus.WaitingForPage -> "Esperando a que la imagen se descargue y decodifique."
        TranslationStatus.RecognizingText -> "ML Kit está localizando los globos de la página."
        TranslationStatus.TranslatingText -> "Enviando los textos al motor de traducción."
        TranslationStatus.Done -> translation?.let {
            if (it.bubbles.isEmpty()) {
                "No se detectó texto en esta página. Prueba otra página o revisa la detección."
            } else {
                "${it.bubbles.size} viñetas · ${it.engineName}\nOCR ${it.ocrDurationMs} ms · Traducción ${it.translateDurationMs} ms"
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
                TranslationStatus.TranslatingText -> CircularProgressIndicator(
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
