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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.hippo.ehviewer.translatortest.TranslatorSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaViewerScreen(
    book: MangaBook,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { TranslatorSettings(context) }

    var currentPageIndex by remember { mutableIntStateOf(0) }
    var isTranslationEnabled by remember { mutableStateOf(true) }
    var selectedBubble by remember { mutableStateOf<TranslatedBubble?>(null) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    var isBatchDialogOpen by remember { mutableStateOf(false) }

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

    // Trigger translation for current page
    LaunchedEffect(
        currentPageIndex,
        currentBitmap,
        isTranslationEnabled,
        settings.engine,
        settings.sourceLang,
        settings.targetLang,
        settings.geminiPromptTemplate,
    ) {
        val bitmap = currentBitmap
        if (isTranslationEnabled && bitmap != null && !translationCache.containsKey(currentPageIndex)) {
            isTranslating = true
            val result = MangaTranslator.translatePage(
                pageIndex = currentPageIndex,
                bitmap = bitmap,
                sourceLang = settings.sourceLang,
                targetLang = settings.targetLang,
                promptTemplate = settings.geminiPromptTemplate,
                engine = settings.engine,
                geminiApiKeys = settings.getGeminiApiKeys(),
                geminiModel = settings.geminiModel,
            )
            translationCache[currentPageIndex] = result
            isTranslating = false
        }
    }

    // Background pre-translation of upcoming pages
    LaunchedEffect(
        currentPageIndex,
        isTranslating,
        settings.autoPretranslate,
        settings.batchSize,
        settings.engine,
        settings.sourceLang,
        settings.targetLang,
        settings.geminiPromptTemplate,
    ) {
        if (!isTranslating && settings.autoPretranslate && isTranslationEnabled) {
            val maxAhead = minOf(currentPageIndex + settings.batchSize, book.pages.size - 1)
            val keys = settings.getGeminiApiKeys()
            for (nextPage in (currentPageIndex + 1)..maxAhead) {
                if (!translationCache.containsKey(nextPage)) {
                    val bmp = withContext(Dispatchers.IO) {
                        MangaLoader.loadBitmap(context, book.pages[nextPage].uriString)
                    }
                    if (bmp != null) {
                        val trans = MangaTranslator.translatePage(
                            pageIndex = nextPage,
                            bitmap = bmp,
                            sourceLang = settings.sourceLang,
                            targetLang = settings.targetLang,
                            promptTemplate = settings.geminiPromptTemplate,
                            engine = settings.engine,
                            geminiApiKeys = keys,
                            geminiModel = settings.geminiModel,
                        )
                        translationCache[nextPage] = trans
                    }
                }
            }
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
                    IconButton(onClick = { settings.isManipulationMode = !settings.isManipulationMode }) {
                        Icon(
                            imageVector = Icons.Default.TouchApp,
                            contentDescription = "Manipular burbujas",
                            tint = if (settings.isManipulationMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                        )
                    }

                    IconButton(onClick = { isBatchDialogOpen = true }) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Traducir en Lote",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }

                    IconButton(onClick = { isSettingsOpen = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Ajustes",
                        )
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
                                    text = if (settings.engine == TranslatorSettings.ENGINE_GEMINI) {
                                        "Traduciendo con Gemini IA..."
                                    } else {
                                        "Traduciendo viñetas con ML Kit..."
                                    },
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
                                    MetricChip(label = "🔤 ${settings.sourceLang.uppercase()} ➔ ${settings.targetLang.uppercase()}")
                                    if (settings.isManipulationMode) {
                                        MetricChip(label = "✋ Modo táctil")
                                    }
                                    if (settings.bubbleOrientation == TranslatorSettings.ORIENTATION_VERTICAL) {
                                        MetricChip(label = "📖 Manga Vertical")
                                    }
                                    if (settings.dynamicBubbleSize) {
                                        MetricChip(label = "📐 Auto-ajuste")
                                    }
                                    MetricChip(label = "⚡ OCR: ${trans.ocrDurationMs}ms")
                                    MetricChip(label = "🌐 Trad: ${trans.translateDurationMs}ms")
                                    MetricChip(label = "⏱️ Total: ${trans.totalDurationMs}ms")
                                    MetricChip(label = "💬 ${trans.bubbles.size} viñetas")
                                    MetricChip(label = "🤖 ${trans.engineName}")
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
                            globalBubbleScale = settings.globalBubbleScale,
                            horizontalExpansion = settings.horizontalExpansion,
                            globalFontScale = settings.globalFontScale,
                            bubbleOpacity = settings.bubbleOpacity,
                            dynamicBubbleSize = settings.dynamicBubbleSize,
                            bubbleOrientation = settings.bubbleOrientation,
                            bubbleShape = settings.bubbleShape,
                            uppercaseScanlation = settings.uppercaseScanlation,
                            preventCollisions = settings.preventCollisions,
                            borderThickness = settings.borderThickness,
                            isManipulationEnabled = settings.isManipulationMode,
                            onBubbleClick = { selectedBubble = it },
                            onBubbleUpdated = { updatedBubble ->
                                val updatedList = pageTrans.bubbles.map {
                                    if (it.id == updatedBubble.id) updatedBubble else it
                                }
                                translationCache[currentPageIndex] = pageTrans.copy(bubbles = updatedList)
                            },
                        )
                    }
                }
            }
        }
    }

    selectedBubble?.let { bubble ->
        BubbleEditDialog(
            bubble = bubble,
            onDismiss = { selectedBubble = null },
            onSave = { updatedBubble ->
                val pageTrans = translationCache[currentPageIndex]
                if (pageTrans != null) {
                    val updatedList = pageTrans.bubbles.map {
                        if (it.id == updatedBubble.id) updatedBubble else it
                    }
                    translationCache[currentPageIndex] = pageTrans.copy(bubbles = updatedList)
                }
                selectedBubble = null
            },
        )
    }

    if (isSettingsOpen) {
        SettingsDialog(
            settings = settings,
            onLanguagesChanged = { translationCache.clear() },
            onDismiss = { isSettingsOpen = false },
        )
    }

    if (isBatchDialogOpen) {
        BatchTranslateDialog(
            context = context,
            book = book,
            currentPageIndex = currentPageIndex,
            settings = settings,
            translationCache = translationCache,
            onDismiss = { isBatchDialogOpen = false },
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
