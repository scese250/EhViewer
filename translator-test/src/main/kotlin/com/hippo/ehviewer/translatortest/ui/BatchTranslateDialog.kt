package com.hippo.ehviewer.translatortest.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.translatortest.MangaBook
import com.hippo.ehviewer.translatortest.MangaLoader
import com.hippo.ehviewer.translatortest.MangaTranslator
import com.hippo.ehviewer.translatortest.PageTranslation
import com.hippo.ehviewer.translatortest.TranslatorSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BatchTranslateDialog(
    context: Context,
    book: MangaBook,
    currentPageIndex: Int,
    settings: TranslatorSettings,
    translationCache: MutableMap<Int, PageTranslation>,
    onDismiss: () -> Unit,
) {
    val remainingPages = book.pages.size - currentPageIndex
    var selectedOption by remember { mutableIntStateOf(minOf(3, remainingPages)) }
    var isRunning by remember { mutableStateOf(false) }
    var currentProgress by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("") }
    var translationJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = {
            if (!isRunning) onDismiss()
        },
        title = {
            Text(
                text = "Traducción en Lote",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (isRunning) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = currentProgress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Progreso: ${(currentProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val srcName = TranslatorSettings.getLanguageName(settings.sourceLang)
                    val tgtName = TranslatorSettings.getLanguageName(settings.targetLang)
                    Text(
                        text = "Traducir de $srcName a $tgtName usando ${if (settings.engine == TranslatorSettings.ENGINE_GEMINI) "Gemini IA" else "Google Translate"}:",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val options = listOf(
                        3 to "Siguientes 3 páginas",
                        5 to "Siguientes 5 páginas",
                        10 to "Siguientes 10 páginas",
                        remainingPages to "Todas las páginas restantes ($remainingPages pág.)",
                    ).filter { it.first in 1..remainingPages }.distinctBy { it.first }

                    options.forEach { (count, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            RadioButton(
                                selected = selectedOption == count,
                                onClick = { selectedOption = count },
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isRunning) {
                OutlinedButton(
                    onClick = {
                        translationJob?.cancel()
                        isRunning = false
                        onDismiss()
                    },
                ) {
                    Text("Cancelar")
                }
            } else {
                Button(
                    onClick = {
                        isRunning = true
                        val targetCount = selectedOption.coerceAtMost(remainingPages)
                        val startIndex = currentPageIndex
                        val endIndex = minOf(startIndex + targetCount, book.pages.size)

                        translationJob = scope.launch {
                            val keys = settings.getGeminiApiKeys()
                            for (i in startIndex until endIndex) {
                                if (translationCache.containsKey(i)) {
                                    currentProgress = (i - startIndex + 1).toFloat() / targetCount
                                    continue
                                }
                                statusText = "Página ${i + 1} de $endIndex (${book.pages[i].name})..."
                                val bitmap = withContext(Dispatchers.IO) {
                                    MangaLoader.loadBitmap(context, book.pages[i].uriString)
                                }
                                if (bitmap != null) {
                                    val trans = MangaTranslator.translatePage(
                                        pageIndex = i,
                                        bitmap = bitmap,
                                        sourceLang = settings.sourceLang,
                                        targetLang = settings.targetLang,
                                        promptTemplate = settings.geminiPromptTemplate,
                                        engine = settings.engine,
                                        geminiApiKeys = keys,
                                        geminiModel = settings.geminiModel,
                                        grouping = settings.groupingConfig(),
                                    )
                                    translationCache[i] = trans
                                }
                                currentProgress = (i - startIndex + 1).toFloat() / targetCount
                            }
                            isRunning = false
                            onDismiss()
                        }
                    },
                ) {
                    Text("Iniciar Traducción")
                }
            }
        },
        dismissButton = {
            if (!isRunning) {
                TextButton(onClick = onDismiss) {
                    Text("Cerrar")
                }
            }
        },
    )
}
