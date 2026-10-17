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

@Composable
fun SettingsDialog(
    settings: TranslatorSettings,
    onDismiss: () -> Unit,
) {
    var isTestingKey by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTestSuccess by remember { mutableStateOf(false) }
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
                                text = "💡 Si ingresas varias claves, rotarán automáticamente si una alcanza el límite de cuota (429).",
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

                            Spacer(modifier = Modifier.height(8.dp))

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

                // Section 2: Globos y Fuentes
                Text(
                    text = "Tamaño de Globos y Fuentes (Global)",
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
                        text = "Tamaño: ${"%.2f".format(settings.globalBubbleScale)}x",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(110.dp),
                    )
                    Slider(
                        value = settings.globalBubbleScale,
                        onValueChange = { settings.globalBubbleScale = it },
                        valueRange = 0.8f..2.5f,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Expansión: ${"%.1f".format(settings.horizontalExpansion)}x",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(110.dp),
                    )
                    Slider(
                        value = settings.horizontalExpansion,
                        onValueChange = { settings.horizontalExpansion = it },
                        valueRange = 1.0f..3.5f,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Fuente: ${"%.2f".format(settings.globalFontScale)}x",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(110.dp),
                    )
                    Slider(
                        value = settings.globalFontScale,
                        onValueChange = { settings.globalFontScale = it },
                        valueRange = 0.6f..2.0f,
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
