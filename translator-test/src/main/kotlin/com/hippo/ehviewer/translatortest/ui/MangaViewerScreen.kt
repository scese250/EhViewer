package com.hippo.ehviewer.translatortest.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hippo.ehviewer.translatortest.MangaBook
import com.hippo.ehviewer.translatortest.MangaLoader
import com.hippo.ehviewer.translatortest.MangaTranslator
import com.hippo.ehviewer.translatortest.PageTranslation
import com.hippo.ehviewer.translatortest.TranslatedBubble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaViewerScreen(
    book: MangaBook,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var isTranslationEnabled by remember { mutableStateOf(true) }
    var targetLang by remember { mutableStateOf("es") }
    var isLangMenuExpanded by remember { mutableStateOf(false) }
    var selectedBubble by remember { mutableStateOf<TranslatedBubble?>(null) }

    val translationCache = remember { mutableStateMapOf<Int, PageTranslation>() }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isBitmapLoading by remember { mutableStateOf(false) }
    var isTranslating by remember { mutableStateOf(false) }

    val currentPage = book.pages.getOrNull(currentPageIndex)

    // Load bitmap when page changes
    LaunchedEffect(currentPageIndex, currentPage?.uriString) {
        if (currentPage == null) return@LaunchedEffect
        isBitmapLoading = true
        currentBitmap = withContext(Dispatchers.IO) {
            MangaLoader.loadBitmap(context, currentPage.uriString)
        }
        isBitmapLoading = false
    }

    // Trigger translation when page, bitmap, or target language changes
    LaunchedEffect(currentPageIndex, currentBitmap, isTranslationEnabled, targetLang) {
        val bitmap = currentBitmap
        if (isTranslationEnabled && bitmap != null && !translationCache.containsKey(currentPageIndex)) {
            isTranslating = true
            val result = MangaTranslator.translatePage(
                pageIndex = currentPageIndex,
                bitmap = bitmap,
                targetLang = targetLang,
            )
            translationCache[currentPageIndex] = result
            isTranslating = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = book.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Pág. ${currentPageIndex + 1} de ${book.pages.size} (${currentPage?.name.orEmpty()})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver",
                        )
                    }
                },
                actions = {
                    Box {
                        TextButton(onClick = { isLangMenuExpanded = true }) {
                            Text(
                                text = targetLang.uppercase(),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        DropdownMenu(
                            expanded = isLangMenuExpanded,
                            onDismissRequest = { isLangMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Español (ES)") },
                                onClick = {
                                    if (targetLang != "es") {
                                        targetLang = "es"
                                        translationCache.clear()
                                    }
                                    isLangMenuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("English (EN)") },
                                onClick = {
                                    if (targetLang != "en") {
                                        targetLang = "en"
                                        translationCache.clear()
                                    }
                                    isLangMenuExpanded = false
                                },
                            )
                        }
                    }

                    IconButton(onClick = { isTranslationEnabled = !isTranslationEnabled }) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = "Alternar traducción",
                            tint = if (isTranslationEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    // Performance & Status Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when {
                            isTranslating -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Traduciendo viñetas con ML Kit...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }

                            isTranslationEnabled && translationCache.containsKey(currentPageIndex) -> {
                                val trans = translationCache[currentPageIndex]!!
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                ) {
                                    MetricChip(label = "⚡ OCR: ${trans.ocrDurationMs}ms")
                                    MetricChip(label = "🌐 Trad: ${trans.translateDurationMs}ms")
                                    MetricChip(label = "⏱️ Total: ${trans.totalDurationMs}ms")
                                    MetricChip(label = "💬 ${trans.bubbles.size} viñetas")
                                }
                            }

                            !isTranslationEnabled -> {
                                Text(
                                    text = "Traducción desactivada",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            else -> {
                                Text(
                                    text = "Listo",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // Navigation Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { if (currentPageIndex > 0) currentPageIndex-- },
                            enabled = currentPageIndex > 0,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Página anterior",
                            )
                        }

                        val maxIndex = (book.pages.size - 1).coerceAtLeast(1)
                        Slider(
                            value = currentPageIndex.toFloat(),
                            onValueChange = { currentPageIndex = it.toInt() },
                            valueRange = 0f..maxIndex.toFloat(),
                            steps = (book.pages.size - 2).coerceAtLeast(0),
                            modifier = Modifier.weight(1f),
                        )

                        IconButton(
                            onClick = { if (currentPageIndex < book.pages.size - 1) currentPageIndex++ },
                            enabled = currentPageIndex < book.pages.size - 1,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Página siguiente",
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = currentBitmap
            if (isBitmapLoading || bitmap == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )

                if (isTranslationEnabled) {
                    val pageTrans = translationCache[currentPageIndex]
                    if (pageTrans != null) {
                        TranslationOverlay(
                            bubbles = pageTrans.bubbles,
                            bitmapWidth = bitmap.width,
                            bitmapHeight = bitmap.height,
                            onBubbleClick = { selectedBubble = it },
                        )
                    }
                }
            }
        }
    }

    selectedBubble?.let { bubble ->
        AlertDialog(
            onDismissRequest = { selectedBubble = null },
            title = { Text(text = "Detalle de Viñeta") },
            text = {
                Column {
                    Text(
                        text = "🇯🇵 Japonés Original:",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = bubble.originalText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "🌐 Traducción (${targetLang.uppercase()}):",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = bubble.translatedText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedBubble = null }) {
                    Text(text = "Cerrar")
                }
            },
        )
    }
}

@Composable
private fun MetricChip(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
