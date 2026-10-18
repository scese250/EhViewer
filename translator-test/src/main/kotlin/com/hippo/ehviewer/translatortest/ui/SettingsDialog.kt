package com.hippo.ehviewer.translatortest.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.hippo.ehviewer.translatortest.MangaTranslator
import com.hippo.ehviewer.translatortest.TranslatorSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    settings: TranslatorSettings,
    onLanguagesChanged: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    var isTestingKey by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTestSuccess by remember { mutableStateOf(false) }
    var isSourceExpanded by remember { mutableStateOf(false) }
    var isTargetExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = {
            settings.save()
            onDismiss()
        },
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
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = isSourceExpanded,
                        onDismissRequest = { isSourceExpanded = false },
                    ) {
                        TranslatorSettings.SOURCE_LANGUAGES.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    if (settings.sourceLang != code) {
                                        settings.sourceLang = code
                                        onLanguagesChanged()
                                    }
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
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = isTargetExpanded,
                        onDismissRequest = { isTargetExpanded = false },
                    ) {
                        TranslatorSettings.TARGET_LANGUAGES.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    if (settings.targetLang != code) {
                                        settings.targetLang = code
                                        onLanguagesChanged()
                                    }
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
                                        val res = MangaTranslator.testGeminiConnection(
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Margen de separación: ${settings.collisionGap.toInt()}px",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(170.dp),
                        )
                        Slider(
                            value = settings.collisionGap,
                            onValueChange = { settings.collisionGap = it },
                            valueRange = 6f..30f,
                            modifier = Modifier.weight(1f),
                        )
                    }
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

                // Section 3: Pre-traducción en segundo plano
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
                        text = "Pretraducir siguientes páginas al leer",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = settings.autoPretranslate,
                        onCheckedChange = { settings.autoPretranslate = it },
                    )
                }

                if (settings.autoPretranslate) {
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
                            value = settings.batchSize.toFloat(),
                            onValueChange = { settings.batchSize = it.toInt() },
                            valueRange = 1f..10f,
                            steps = 8,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    settings.save()
                    onDismiss()
                },
            ) {
                Text("Listo")
            }
        },
    )
}
