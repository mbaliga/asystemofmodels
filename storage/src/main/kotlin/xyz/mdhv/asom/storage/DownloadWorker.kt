package xyz.mdhv.asom.storage

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import xyz.mdhv.asom.catalogue.ModelFile

/**
 * Resumable model download (brief §10): WorkManager, wifi-only toggle,
 * SHA-256 verify, `filesDir/models/{modelId}/`. Download events are ledger
 * rows (`egress: download`) written via [DownloadLedgerSink] — plugged in by
 * `:app` so this module stays contract-only otherwise.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val sha256 = inputData.getString(KEY_SHA256) ?: return Result.failure()
        val bytesExpected = inputData.getLong(KEY_BYTES, 0)

        val store = ModelStore(applicationContext)
        val db = StorageDatabase.open(applicationContext)
        val target = store.fileFor(modelId)
        target.parentFile?.mkdirs()

        fun update(status: DownloadStatus, downloaded: Long = 0, error: String? = null) {
            db.dao().upsert(
                ModelDownloadEntity(
                    modelId = modelId, status = status.name,
                    bytesDownloaded = downloaded, bytesTotal = bytesExpected,
                    updatedAt = System.currentTimeMillis(), error = error,
                ),
            )
        }

        update(DownloadStatus.DOWNLOADING)
        return try {
            downloadTo(url, target) { downloaded -> update(DownloadStatus.DOWNLOADING, downloaded) }

            update(DownloadStatus.VERIFYING, target.length())
            if (!ModelStore.verify(target, sha256)) {
                target.delete()
                update(DownloadStatus.FAILED, error = "sha256 mismatch")
                return Result.failure(workDataOf(KEY_ERROR to "sha256 mismatch"))
            }

            DownloadLedgerBridge.sink?.onDownloadComplete(modelId, target.length())
            update(DownloadStatus.DOWNLOADED, target.length())
            Result.success()
        } catch (e: Exception) {
            update(DownloadStatus.FAILED, error = e.message)
            Result.retry()
        }
    }

    private fun downloadTo(url: String, target: File, onProgress: (Long) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connect()
        var downloaded = 0L
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    downloaded += n
                    onProgress(downloaded)
                }
            }
        }
    }

    companion object {
        const val KEY_MODEL_ID = "modelId"
        const val KEY_URL = "url"
        const val KEY_SHA256 = "sha256"
        const val KEY_BYTES = "bytes"
        const val KEY_ERROR = "error"

        fun enqueue(context: Context, modelId: String, file: ModelFile, wifiOnly: Boolean = true) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_MODEL_ID to modelId,
                        KEY_URL to file.url,
                        KEY_SHA256 to file.sha256,
                        KEY_BYTES to file.bytes,
                    ),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("download-$modelId", ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, modelId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("download-$modelId")
        }
    }
}

/** Ledger write seam (§10: download events are ledger rows, egress: download). */
fun interface DownloadLedgerSink {
    fun onDownloadComplete(modelId: String, bytes: Long)
}

/** Process-wide bridge so the WorkManager worker (no DI) can reach :app's ledger. */
object DownloadLedgerBridge {
    var sink: DownloadLedgerSink? = null
}
