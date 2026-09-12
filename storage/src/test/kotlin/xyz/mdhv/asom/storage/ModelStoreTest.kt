package xyz.mdhv.asom.storage

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
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
}
