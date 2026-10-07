package xyz.mdhv.asom.storage

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure-logic slice of ModelStore.verify — no Android runtime needed. */
class ModelStoreTest {

    @Test
    fun `verify accepts a matching sha256`() {
        val file = File.createTempFile("model", ".bin").apply {
            writeBytes("hello asom weights".toByteArray())
            deleteOnExit()
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertTrue(ModelStore.verify(file, sha))
    }

    @Test
    fun `verify rejects a tampered file`() {
        val file = File.createTempFile("model", ".bin").apply {
            writeBytes("original".toByteArray())
            deleteOnExit()
        }
        val originalSha = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        file.writeBytes("tampered".toByteArray())
        assertFalse(ModelStore.verify(file, originalSha))
    }

    @Test
    fun `verify is case-insensitive on hex`() {
        val file = File.createTempFile("model", ".bin").apply {
            writeBytes("x".toByteArray())
            deleteOnExit()
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertTrue(ModelStore.verify(file, sha.uppercase()))
    }

    @Test
    fun `a model with only a staging file is not downloaded`() {
        val store = ModelStore(tempRoot())
        store.partFileFor("llama-3.3-70b").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(64))
        }
        assertFalse(store.isDownloaded("llama-3.3-70b"), "an unverified .part must never count as downloaded")
        assertEquals(emptyList(), store.downloadedModelIds())
    }

    @Test
    fun `a published weights file is downloaded`() {
        val store = ModelStore(tempRoot())
        store.fileFor("llama-3.3-70b").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(64))
        }
        assertTrue(store.isDownloaded("llama-3.3-70b"))
        assertEquals(listOf("llama-3.3-70b"), store.downloadedModelIds())
    }

    @Test
    fun `traversal model ids are rejected`() {
        // Uri.getPathSegments() percent-decodes each segment after splitting,
        // so a single segment can arrive already containing separators.
        val hostile = listOf("../../app_models", "..", "a/b", "", ".hidden", "-dash", "a b", "a%2Fb", "/etc/passwd")
        for (id in hostile) {
            assertFalse(ModelStore.isValidModelId(id), "must reject '$id'")
        }
    }

    @Test
    fun `catalogue-shaped model ids are accepted`() {
        for (id in listOf("llama-3.3-70b", "gpt-4o-mini", "qwen2.5_7b", "a")) {
            assertTrue(ModelStore.isValidModelId(id), "must accept '$id'")
        }
    }

    @Test
    fun `containment check rejects a path that escapes the root`() {
        val root = tempRoot()
        assertTrue(ModelStore.isWithin(root, File(root, "llama/weights.gguf")))
        assertFalse(ModelStore.isWithin(root, File(root, "../../app_models/weights.gguf")))
        assertFalse(ModelStore.isWithin(root, root))
    }

    private fun tempRoot(): File {
        val dir = File.createTempFile("asom-models", "")
        dir.delete()
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }
}
