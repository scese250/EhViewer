package com.hippo.ehviewer.translatortest

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hippo.ehviewer.translatortest.ui.MangaViewerScreen
import com.hippo.ehviewer.translatortest.ui.PickerScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppContent()
                }
            }
        }
    }

    @Composable
    private fun AppContent() {
        var currentBook by remember { mutableStateOf<MangaBook?>(null) }
        var isLoading by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        if (currentBook != null) {
            BackHandler {
                currentBook = null
            }
            MangaViewerScreen(
                book = currentBook!!,
                onBack = { currentBook = null },
            )
        } else {
            PickerScreen(
                isLoading = isLoading,
                onSelectCbz = { uri ->
                    scope.launch {
                        isLoading = true
                        try {
                            val book = MangaLoader.loadFromCbz(this@MainActivity, uri)
                            if (book.pages.isEmpty()) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "No se encontraron imágenes en el archivo",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                currentBook = book
                            }
                        } catch (e: Exception) {
                            Toast.makeText(
                                this@MainActivity,
                                "Error al cargar CBZ: ${e.message}",
                                Toast.LENGTH_LONG,
                            ).show()
                        } finally {
                            isLoading = false
                        }
                    }
                },
                onSelectFolder = { uri ->
                    scope.launch {
                        isLoading = true
                        try {
                            val book = MangaLoader.loadFromFolder(this@MainActivity, uri)
                            if (book.pages.isEmpty()) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "No se encontraron imágenes en la carpeta",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                currentBook = book
                            }
                        } catch (e: Exception) {
                            Toast.makeText(
                                this@MainActivity,
                                "Error al cargar carpeta: ${e.message}",
                                Toast.LENGTH_LONG,
                            ).show()
                        } finally {
                            isLoading = false
                        }
                    }
                },
            )
        }
    }
}
