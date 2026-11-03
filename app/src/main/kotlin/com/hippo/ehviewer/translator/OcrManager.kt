package com.hippo.ehviewer.translator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import splitties.init.appCtx

data class OcrTextBlock(
    val text: String,
    val boundingBox: Rect,
)

object OcrManager {
    @Volatile
    private var modelAvailableOverride: Boolean? = null

    var isModelAvailable: Boolean
        get() = modelAvailableOverride ?: runCatching {
            val prefs = appCtx.getSharedPreferences("manga_translator_prefs", Context.MODE_PRIVATE)
            prefs.getBoolean("ocr_model_downloaded", false)
        }.getOrDefault(false)
        internal set(value) {
            modelAvailableOverride = value
        }

    fun isModelAvailable(): Boolean = isModelAvailable

    private var recognizer: TextRecognizer? = null

    private fun getRecognizer(): TextRecognizer = recognizer ?: synchronized(this) {
        recognizer ?: TextRecognition.getClient(
            JapaneseTextRecognizerOptions.Builder().build(),
        ).also { recognizer = it }
    }

    suspend fun checkModuleAvailability(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = ModuleInstall.getClient(context)
            val response = client.areModulesAvailable(getRecognizer()).awaitTask()
            val available = response.areModulesAvailable()
            isModelAvailable = available
            available
        } catch (_: Exception) {
            false
        }
    }

    suspend fun downloadModule(context: Context, onProgress: (Int) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        val client = ModuleInstall.getClient(context)
        val rec = getRecognizer()
        var listener: InstallStatusListener? = null
        try {
            val availability = client.areModulesAvailable(rec).awaitTask()
            if (availability.areModulesAvailable()) {
                isModelAvailable = true
                onProgress(100)
                return@withContext Result.success(Unit)
            }

            val completion = CompletableDeferred<Unit>()
            listener = InstallStatusListener { update ->
                val progressInfo = update.progressInfo
                if (progressInfo != null && progressInfo.totalBytesToDownload > 0L) {
                    val progress = ((progressInfo.bytesDownloaded * 100L) / progressInfo.totalBytesToDownload).toInt()
                    onProgress(progress.coerceIn(0, 100))
                }
                when (update.installState) {
                    ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> {
                        onProgress(100)
                        completion.complete(Unit)
                    }
                    ModuleInstallStatusUpdate.InstallState.STATE_FAILED -> {
                        completion.completeExceptionally(
                            IOException("Module installation failed (code ${update.errorCode})"),
                        )
                    }
                    ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
                        completion.completeExceptionally(CancellationException("Module installation canceled"))
                    }
                }
            }

            val request = ModuleInstallRequest.newBuilder()
                .addApi(rec)
                .setListener(listener)
                .build()

            val response = client.installModules(request).awaitTask()
            if (response.areModulesAlreadyInstalled()) {
                onProgress(100)
            } else {
                completion.await()
            }
            isModelAvailable = true
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            listener?.let { runCatching { client.unregisterListener(it) } }
        }
    }

    fun deleteModule(context: Context) {
        try {
            val client = ModuleInstall.getClient(context)
            recognizer?.let { client.releaseModules(it) }
        } catch (_: Exception) {
        }
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        recognizer = null
        isModelAvailable = false
    }

    suspend fun extractTextBlocks(bitmap: Bitmap): List<OcrTextBlock> = withContext(Dispatchers.Default) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val visionText = getRecognizer().process(image).awaitTask()
        visionText.textBlocks.mapNotNull { block ->
            val text = block.text.trim()
            val box = block.boundingBox
            if (text.isNotBlank() && box != null && !box.isEmpty) {
                OcrTextBlock(text = text, boundingBox = box)
            } else {
                null
            }
        }
    }

    private suspend fun <T> Task<T>.awaitTask(): T {
        if (isComplete) {
            val e = exception
            return if (e == null) {
                if (isCanceled) {
                    throw CancellationException("Task was cancelled.")
                } else {
                    result
                }
            } else {
                throw e
            }
        }
        return suspendCancellableCoroutine { cont ->
            addOnSuccessListener { result -> cont.resume(result) }
            addOnFailureListener { exception -> cont.resumeWithException(exception) }
            addOnCanceledListener { cont.cancel() }
        }
    }
}
