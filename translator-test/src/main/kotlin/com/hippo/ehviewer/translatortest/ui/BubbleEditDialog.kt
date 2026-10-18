package com.hippo.ehviewer.translatortest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hippo.ehviewer.translatortest.TranslatedBubble

@Composable
fun BubbleEditDialog(
    bubble: TranslatedBubble,
    onDismiss: () -> Unit,
    onSave: (TranslatedBubble) -> Unit,
) {
    var editedText by remember { mutableStateOf(bubble.translatedText) }
    var widthScale by remember { mutableFloatStateOf(bubble.widthScale) }
    var heightScale by remember { mutableFloatStateOf(bubble.heightScale) }
    var offsetX by remember { mutableFloatStateOf(bubble.offsetX) }
    var offsetY by remember { mutableFloatStateOf(bubble.offsetY) }
    var isVertical by remember { mutableStateOf(bubble.isVertical) }
    var useCustomFont by remember { mutableStateOf(bubble.customFontSizeSp != null) }
    var customFontSizeSp by remember { mutableFloatStateOf(bubble.customFontSizeSp ?: 12f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Ajustar Viñeta #${bubble.id + 1}",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "🇯🇵 Japonés Original:",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = bubble.originalText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    label = { Text("Texto Traducido") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 5,
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Orientation selector for this bubble
                Text(
                    text = "Orientación de esta viñeta:",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilterChip(
                        selected = isVertical == null,
                        onClick = { isVertical = null },
                        label = { Text("Auto / Global", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = isVertical == true,
                        onClick = { isVertical = true },
                        label = { Text("Vertical (Manga)", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = isVertical == false,
                        onClick = { isVertical = false },
                        label = { Text("Horizontal", fontSize = 11.sp) },
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Posición Manual (Mover en pantalla):",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Mover X: ${offsetX.toInt()}dp",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(105.dp),
                    )
                    Slider(
                        value = offsetX,
                        onValueChange = { offsetX = it },
                        valueRange = -80f..80f,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Mover Y: ${offsetY.toInt()}dp",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(105.dp),
                    )
                    Slider(
                        value = offsetY,
                        onValueChange = { offsetY = it },
                        valueRange = -80f..80f,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Escala de Tamaño:",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Ancho: ${"%.1f".format(widthScale)}x",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(105.dp),
                    )
                    Slider(
                        value = widthScale,
                        onValueChange = { widthScale = it },
                        valueRange = 0.4f..3.0f,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Alto: ${"%.1f".format(heightScale)}x",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(105.dp),
                    )
                    Slider(
                        value = heightScale,
                        onValueChange = { heightScale = it },
                        valueRange = 0.4f..3.0f,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Fuente manual",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = useCustomFont,
                        onCheckedChange = { useCustomFont = it },
                    )
                }

                if (useCustomFont) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Tamaño: ${customFontSizeSp.toInt()} sp",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(105.dp),
                        )
                        Slider(
                            value = customFontSizeSp,
                            onValueChange = { customFontSizeSp = it },
                            valueRange = 8f..28f,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        bubble.copy(
                            translatedText = editedText.trim(),
                            widthScale = widthScale,
                            heightScale = heightScale,
                            customFontSizeSp = if (useCustomFont) customFontSizeSp else null,
                            offsetX = offsetX,
                            offsetY = offsetY,
                            isVertical = isVertical,
                        ),
                    )
                },
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        },
    )
}
