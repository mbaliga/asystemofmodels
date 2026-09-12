package xyz.mdhv.asom.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-memory store + trivial (but real-shaped) wrapping cipher for JVM tests. */
private class FakeStore : VaultStore {
    var wrapped: WrappedBlob? = null
    val keys = linkedMapOf<String, WrappedBlob>()

    override fun loadWrappedDataKey() = wrapped
    override fun saveWrappedDataKey(blob: WrappedBlob) {
        wrapped = blob
    }

    override fun loadKey(providerId: String) = keys[providerId]
    override fun saveKey(providerId: String, blob: WrappedBlob) {
        keys[providerId] = blob
    }

    override fun deleteKey(providerId: String) {
        keys.remove(providerId)
    }

    override fun listProviderIds() = keys.keys.toList()
}

/** XOR "wrap" — stands in for the Keystore master key on the JVM. */
private class FakeWrapper : WrappingCipher {
    override fun wrap(plaintext: ByteArray) =
        WrappedBlob(plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray(), ByteArray(12))

    override fun unwrap(blob: WrappedBlob) =
        blob.ciphertext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
}

class DataKeyVaultTest {

    private val store = FakeStore()
    private val vault = DataKeyVault(FakeWrapper(), store)

    @Test
    fun `store and read back a provider key`() {
        vault.storeKey("openrouter", "sk-or-verysecret")
        assertEquals("sk-or-verysecret", vault.getKey("openrouter"))
        assertTrue(vault.hasKey("openrouter"))
        assertEquals(listOf("openrouter"), vault.providersWithKeys())
    }

    @Test
    fun `one active key per provider — overwrite replaces`() {
        vault.storeKey("groq", "sk-old")
        vault.storeKey("groq", "sk-new")
        assertEquals("sk-new", vault.getKey("groq"))
        assertEquals(listOf("groq"), vault.providersWithKeys())
    }

    @Test
    fun `delete removes the key`() {
        vault.storeKey("anthropic", "sk-ant")
        vault.deleteKey("anthropic")
        assertNull(vault.getKey("anthropic"))
        assertFalse(vault.hasKey("anthropic"))
    }

    @Test
    fun `persisted blobs never contain plaintext key material`() {
        val secret = "sk-PLAINTEXT-MUST-NOT-PERSIST"
        vault.storeKey("openrouter", secret)
        val persisted = store.keys["openrouter"]!!
        val asString = persisted.ciphertext.toString(Charsets.ISO_8859_1)
        assertFalse(secret in asString, "plaintext key found in persisted ciphertext")
        // And the wrapped data key is not the raw data key (wrap is not identity).
        val rawGuess = FakeWrapper().unwrap(store.wrapped!!)
        assertFalse(rawGuess.contentEquals(store.wrapped!!.ciphertext))
    }

    @Test
    fun `data key survives vault restarts (rewrap round-trip)`() {
        vault.storeKey("openrouter", "sk-across-restarts")
        val rebooted = DataKeyVault(FakeWrapper(), store)
        assertEquals("sk-across-restarts", rebooted.getKey("openrouter"))
    }
}

class RedactionTest {

    @Test
    fun `redaction law — no key bytes appear in captured logs`() {
        val secret = "sk-THE-ACTUAL-KEY-BYTES"
        val captured = mutableListOf<String>()
        fun log(message: String) = captured.add(Redaction.redact(message, listOf(secret)))

        // Simulate careless logging paths (§8 redaction law).
        log("storing key $secret for openrouter")
        log("request failed with Authorization: Bearer $secret")
        log("benign message")

        assertTrue(captured.isNotEmpty())
        for (line in captured) {
            assertFalse(secret in line, "key bytes leaked into log line: $line")
        }
        assertEquals("storing key ${Redaction.MASK} for openrouter", captured[0])
    }
}
