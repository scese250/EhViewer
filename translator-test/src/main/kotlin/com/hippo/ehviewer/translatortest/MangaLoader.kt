package com.hippo.ehviewer.translatortest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MangaLoader {
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    private val NUMBER_REGEX = Regex("\\d+")

    suspend fun loadFromCbz(context: Context, uri: Uri): MangaBook = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "cbz_extracted_${System.currentTimeMillis()}").apply { mkdirs() }
        val pages = mutableListOf<MangaPage>()
        var entryIndex = 0

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                    if (!entry.isDirectory && ext in IMAGE_EXTENSIONS) {
                        val outFile = File(cacheDir, "page_%04d.$ext".format(entryIndex))
                        FileOutputStream(outFile).use { fos ->
                            zip.copyTo(fos)
                        }
                        pages.add(MangaPage(entryIndex, entry.name.substringAfterLast('/'), outFile.toURI().toString()))
                        entryIndex++
                    }
                    entry = zip.nextEntry
                }
            }
        }

        val sortedPages = pages.sortedWith { a, b ->
            compareFilenames(a.name, b.name)
        }.mapIndexed { idx, p -> p.copy(index = idx) }

        val title = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".cbz")?.removeSuffix(".zip") ?: "Manga CBZ"
        MangaBook(title, sortedPages)
    }

    suspend fun loadFromFolder(context: Context, treeUri: Uri): MangaBook = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: error("No se pudo abrir la carpeta seleccionada")
        val files = rootDoc.listFiles()
        val imageFiles = files.filter { doc ->
            val ext = doc.name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            ext in IMAGE_EXTENSIONS
        }

        val sorted = imageFiles.sortedWith { a, b ->
            compareFilenames(a.name.orEmpty(), b.name.orEmpty())
        }

        val pages = sorted.mapIndexed { idx, doc ->
            MangaPage(
                index = idx,
                name = doc.name.orEmpty(),
                uriString = doc.uri.toString(),
            )
        }

        val title = rootDoc.name ?: "Carpeta de Manga"
        MangaBook(title, pages)
    }

    fun loadBitmap(context: Context, uriString: String, maxDimension: Int = 2048): Bitmap? {
        val uri = Uri.parse(uriString)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (uri.scheme == "file") {
            BitmapFactory.decodeFile(uri.path, opts)
        } else {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        var sample = 1
        while (opts.outWidth / sample > maxDimension || opts.outHeight / sample > maxDimension) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        return if (uri.scheme == "file") {
            BitmapFactory.decodeFile(uri.path, decodeOpts)
        } else {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        }
    }

    private fun compareFilenames(s1: String, s2: String): Int {
        val num1 = NUMBER_REGEX.find(s1)?.value?.toIntOrNull()
        val num2 = NUMBER_REGEX.find(s2)?.value?.toIntOrNull()
        return if (num1 != null && num2 != null) {
            num1.compareTo(num2)
        } else {
            s1.compareTo(s2, ignoreCase = true)
        }
    }
}
