package xyz.mdhv.asom.storage

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException
import xyz.mdhv.asom.catalogue.ModelFile

/**
 * Resumable model download (brief §10): WorkManager, wifi-only toggle,
 * SHA-256 verify, `filesDir/models/{modelId}/`. Download events are ledger
 * rows (`egress: download`) written via [DownloadLedgerSink] — plugged in by
 * `:app` so this module stays contract-only otherwise.
 *
 * Bytes land in `weights.gguf.part` and are renamed to the shared name only
 * after the hash verifies, so §5.8 never exposes unverified weights. A rerun
 * resumes that staging file with a `Range` request.
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

        if (!ModelStore.isValidModelId(modelId)) {
            return Result.failure(workDataOf(KEY_ERROR to "invalid modelId"))
        }
        // Parsed up front so that everything inside the try below has actually
        // touched the network, and every ledger row there is a real event.
        val parsedUrl = try {
            URL(url)
        } catch (e: MalformedURLException) {
            return Result.failure(workDataOf(KEY_ERROR to "invalid url"))
        }

        // §1.3: no network event without a ledger row. :app installs the sink
        // in Application.onCreate, so a null here means the process is not the
        // asom app — refuse to move bytes rather than move them unrecorded.
        val ledger = DownloadLedgerBridge.sink ?: return retryOrGiveUp("ledger sink absent")

        val store = ModelStore(applicationContext)
        val db = StorageDatabase.open(applicationContext)
        val target = store.fileFor(modelId)
        val part = store.partFileFor(modelId)
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

        val startedAt = System.currentTimeMillis()
        fun ledgerRow(status: Int) =
            ledger.onDownloadEvent(modelId, status, System.currentTimeMillis() - startedAt)

        val missing = bytesExpected - part.length()
        if (bytesExpected > 0 && missing > 0 && (target.parentFile?.usableSpace ?: 0) < missing) {
            part.delete()
            update(DownloadStatus.FAILED, error = "insufficient storage")
            return Result.failure(workDataOf(KEY_ERROR to "insufficient storage"))
        }

        update(DownloadStatus.DOWNLOADING, part.length())
        return try {
            val transferred = downloadTo(parsedUrl, part) { downloaded ->
                update(DownloadStatus.DOWNLOADING, downloaded)
            }

            update(DownloadStatus.VERIFYING, transferred)
            if (!ModelStore.verify(part, sha256)) {
                part.delete()
                update(DownloadStatus.FAILED, error = "sha256 mismatch")
                ledgerRow(HttpURLConnection.HTTP_OK)
                return Result.failure(workDataOf(KEY_ERROR to "sha256 mismatch"))
            }

            target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                update(DownloadStatus.FAILED, error = "publish failed")
                ledgerRow(HttpURLConnection.HTTP_OK)
                return Result.failure(workDataOf(KEY_ERROR to "publish failed"))
            }

            ledgerRow(HttpURLConnection.HTTP_OK)
            update(DownloadStatus.DOWNLOADED, target.length())
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpStatusException) {
            update(DownloadStatus.FAILED, part.length(), "HTTP ${e.code}")
            ledgerRow(e.code)
            if (e.code in 400..499) {
                part.delete()
                Result.failure(workDataOf(KEY_ERROR to "HTTP ${e.code}"))
            } else {
                retryOrGiveUp("HTTP ${e.code}", part)
            }
        } catch (e: Exception) {
            update(DownloadStatus.FAILED, part.length(), e.message)
            ledgerRow(STATUS_NO_RESPONSE)
            retryOrGiveUp(e.message ?: e.javaClass.simpleName, part)
        }
    }

    /**
     * Retries are bounded: a permanently broken URL or a device that keeps
     * filling up would otherwise re-transfer the whole file forever.
     */
    private fun retryOrGiveUp(error: String, part: File? = null): Result =
        if (runAttemptCount >= MAX_ATTEMPTS - 1) {
            part?.delete()
            Result.failure(workDataOf(KEY_ERROR to error))
        } else {
            Result.retry()
        }

    private fun downloadTo(url: URL, part: File, onProgress: (Long) -> Unit): Long {
        val alreadyHave = part.length()
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (alreadyHave > 0) setRequestProperty("Range", "bytes=$alreadyHave-")
        }
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) throw HttpStatusException(code)

            val resuming = code == HttpURLConnection.HTTP_PARTIAL && alreadyHave > 0
            var downloaded = if (resuming) alreadyHave else 0L
            var lastProgressMs = 0L
            connection.inputStream.use { input ->
                FileOutputStream(part, resuming).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        // One Room transaction per 64 KiB would be ~650k
                        // commits for the catalogue's largest model.
                        val now = System.currentTimeMillis()
                        if (now - lastProgressMs >= PROGRESS_INTERVAL_MS) {
                            lastProgressMs = now
                            onProgress(downloaded)
                        }
                    }
                }
            }
            return downloaded
        } finally {
            connection.disconnect()
        }
    }

    private class HttpStatusException(val code: Int) : Exception("HTTP $code")

    companion object {
        const val KEY_MODEL_ID = "modelId"
        const val KEY_URL = "url"
        const val KEY_SHA256 = "sha256"
        const val KEY_BYTES = "bytes"
        const val KEY_ERROR = "error"

        /** Ledger status for a transfer that never got an HTTP response. */
        const val STATUS_NO_RESPONSE = 0

        private const val MAX_ATTEMPTS = 5
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val PROGRESS_INTERVAL_MS = 1_000L

        fun enqueue(context: Context, modelId: String, file: ModelFile, wifiOnly: Boolean = true) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
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

/**
 * Ledger write seam (§10: download events are ledger rows, egress: download).
 * Called from the worker's own thread on every terminal outcome — success,
 * hash mismatch and failure alike — and may block on the row write.
 */
fun interface DownloadLedgerSink {
    fun onDownloadEvent(modelId: String, status: Int, latencyMs: Long)
}

/** Process-wide bridge so the WorkManager worker (no DI) can reach :app's ledger. */
object DownloadLedgerBridge {
    var sink: DownloadLedgerSink? = null
}
