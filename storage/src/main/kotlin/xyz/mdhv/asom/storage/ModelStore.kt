package xyz.mdhv.asom.storage

import android.content.Context
import java.io.File
import java.security.MessageDigest
import xyz.mdhv.asom.catalogue.ModelEntry

/**
 * Storage layout law (brief §10): `filesDir/models/{modelId}/`. Read-only
 * sharing to paired apps goes through the `xyz.mdhv.asom.models`
 * ContentProvider (§5.8) built on top of this.
 *
 * Bytes in flight live in a `.part` staging file: the final name only ever
 * appears after the SHA-256 verify, so §5.8 can never hand out an fd to
 * unverified weights.
 */
class ModelStore internal constructor(private val root: File) {

    constructor(context: Context) : this(File(context.filesDir, "models"))

    init {
        root.mkdirs()
    }

    fun dirFor(modelId: String): File = File(root, modelId)

    /** The single weights file for a model (v1: one file per model, §6 fixture shape). */
    fun fileFor(modelId: String, fileName: String = WEIGHTS_FILE): File =
        File(dirFor(modelId), fileName)

    /** Staging name the download writes into until the hash verifies (§10). */
    fun partFileFor(modelId: String, fileName: String = WEIGHTS_FILE): File =
        File(dirFor(modelId), fileName + PART_SUFFIX)

    /** True only for a published (verified, renamed) weights file. */
    fun isDownloaded(modelId: String): Boolean = fileFor(modelId).exists()

    /** Containment guard for paths built from caller-supplied ids (§5.8). */
    fun isInsideRoot(file: File): Boolean = isWithin(root, file)

    fun bytesOnDisk(modelId: String): Long =
        dirFor(modelId).listFiles()?.sumOf { it.length() } ?: 0

    fun totalBytesOnDisk(): Long = root.listFiles()?.sumOf { dir -> dir.listFiles()?.sumOf { it.length() } ?: 0 } ?: 0

    fun evict(modelId: String) {
        dirFor(modelId).deleteRecursively()
    }

    fun downloadedModelIds(): List<String> =
        root.listFiles()?.filter { it.isDirectory && File(it, WEIGHTS_FILE).exists() }?.map { it.name } ?: emptyList()

    companion object {
        const val WEIGHTS_FILE = "weights.gguf"
        const val PART_SUFFIX = ".part"

        private val MODEL_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

        /**
         * `Uri.getPathSegments()` percent-decodes each segment after splitting,
         * so a `%2F` arrives as a real separator — ids are matched against an
         * allowlist rather than scanned for bad characters.
         */
        fun isValidModelId(modelId: String): Boolean =
            MODEL_ID.matches(modelId) && !modelId.contains("..")

        fun isWithin(root: File, file: File): Boolean =
            file.canonicalPath.startsWith(root.canonicalPath + File.separator)

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
