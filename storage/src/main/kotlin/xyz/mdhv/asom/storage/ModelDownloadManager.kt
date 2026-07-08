package xyz.mdhv.asom.storage

import android.content.Context
import kotlinx.coroutines.flow.Flow
import xyz.mdhv.asom.catalogue.Catalogue

/**
 * Dashboard-facing facade over [ModelStore] + [DownloadWorker] + Room state
 * (brief §10: download, progress, pin, evict, per-model/total storage stats).
 */
class ModelDownloadManager(
    private val context: Context,
    private val catalogue: () -> Catalogue,
) {
    private val store = ModelStore(context)
    private val db = StorageDatabase.open(context)

    fun observeAll(): Flow<List<ModelDownloadEntity>> = db.dao().observeAll()

    fun download(modelId: String, wifiOnly: Boolean = true) {
        val model = catalogue().model(modelId) ?: return
        val file = ModelStore.primaryFile(model) ?: return
        DownloadWorker.enqueue(context, modelId, file, wifiOnly)
    }

    fun cancel(modelId: String) {
        DownloadWorker.cancel(context, modelId)
    }

    fun pin(modelId: String, pinned: Boolean) {
        db.dao().setPinned(modelId, pinned)
    }

    fun isPinned(modelId: String): Boolean = db.dao().get(modelId)?.pinned == true

    /** Deletes the weights; refuses if pinned (dashboard must unpin first). */
    fun evict(modelId: String): Boolean {
        if (isPinned(modelId)) return false
        store.evict(modelId)
        db.dao().delete(modelId)
        return true
    }

    fun totalBytesOnDisk(): Long = store.totalBytesOnDisk()

    fun bytesOnDisk(modelId: String): Long = store.bytesOnDisk(modelId)

    fun isDownloaded(modelId: String): Boolean = store.isDownloaded(modelId)

    /** Weights file for read-only fd sharing (§5.8) — null when not downloaded. */
    fun fileFor(modelId: String) = store.fileFor(modelId).takeIf { it.exists() }
}
