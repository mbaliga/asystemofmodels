package xyz.mdhv.asom.storage

import android.content.Context
import java.io.File
import java.security.MessageDigest
import xyz.mdhv.asom.catalogue.ModelEntry

/**
 * Storage layout law (brief §10): `filesDir/models/{modelId}/`. Read-only
 * sharing to paired apps goes through the `xyz.mdhv.asom.models`
 * ContentProvider (§5.8) built on top of this.
 */
class ModelStore(context: Context) {

    private val root = File(context.filesDir, "models").apply { mkdirs() }

    fun dirFor(modelId: String): File = File(root, modelId)

    /** The single weights file for a model (v1: one file per model, §6 fixture shape). */
    fun fileFor(modelId: String, fileName: String = "weights.gguf"): File =
        File(dirFor(modelId), fileName)

    fun isDownloaded(modelId: String): Boolean = dirFor(modelId).let { dir ->
        dir.exists() && dir.listFiles()?.isNotEmpty() == true
    }

    fun bytesOnDisk(modelId: String): Long =
        dirFor(modelId).listFiles()?.sumOf { it.length() } ?: 0

    fun totalBytesOnDisk(): Long = root.listFiles()?.sumOf { dir -> dir.listFiles()?.sumOf { it.length() } ?: 0 } ?: 0

    fun evict(modelId: String) {
        dirFor(modelId).deleteRecursively()
    }

    fun downloadedModelIds(): List<String> =
        root.listFiles()?.filter { it.isDirectory && it.listFiles()?.isNotEmpty() == true }?.map { it.name } ?: emptyList()

    companion object {
        /** Streaming SHA-256 verify against the catalogue-declared hash (§10). */
        fun verify(file: File, expectedSha256: String): Boolean {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            return actual.equals(expectedSha256, ignoreCase = true)
        }

        fun primaryFile(model: ModelEntry) = model.files.firstOrNull()
    }
}
