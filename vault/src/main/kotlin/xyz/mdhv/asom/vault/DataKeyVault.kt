package xyz.mdhv.asom.vault

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * BYOK vault core (§8): a Keystore-wrapped random data key encrypts one
 * active key per provider with AES-256-GCM. Pure JVM crypto — the only
 * Android dependency is behind [WrappingCipher]/[VaultStore], so the vault
 * logic (and the redaction law) is unit-testable on a bare JDK.
 *
 * Write path law (§1.4): the ONLY caller of [storeKey] is the dashboard
 * Keys tab. No API accepts or returns key material.
 */
class DataKeyVault(
    private val wrapper: WrappingCipher,
    private val store: VaultStore,
    private val random: SecureRandom = SecureRandom(),
) {
    private val lock = Any()
    private var dataKey: ByteArray? = null

    private fun dataKey(): ByteArray = synchronized(lock) {
        dataKey?.let { return it }
        val existing = store.loadWrappedDataKey()
        val key = if (existing != null) {
            wrapper.unwrap(existing)
        } else {
            ByteArray(32).also { random.nextBytes(it) }
                .also { store.saveWrappedDataKey(wrapper.wrap(it)) }
        }
        dataKey = key
        key
    }

    fun storeKey(providerId: String, key: String) {
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey(), "AES"), GCMParameterSpec(128, nonce))
        }
        store.saveKey(providerId, WrappedBlob(cipher.doFinal(key.toByteArray(Charsets.UTF_8)), nonce))
    }

    fun getKey(providerId: String): String? {
        val blob = store.loadKey(providerId) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey(), "AES"), GCMParameterSpec(128, blob.nonce))
        }
        return cipher.doFinal(blob.ciphertext).toString(Charsets.UTF_8)
    }

    fun deleteKey(providerId: String) = store.deleteKey(providerId)

    fun hasKey(providerId: String): Boolean = store.loadKey(providerId) != null

    fun providersWithKeys(): List<String> = store.listProviderIds()
}

/**
 * Redaction law (§8): every logging path renders key material as
 * `[REDACTED]`. Pass anything that might contain secrets through here
 * before it reaches a logger.
 */
object Redaction {
    const val MASK = "[REDACTED]"

    fun redact(message: String, secrets: Collection<String>): String {
        var out = message
        for (secret in secrets) {
            if (secret.isNotEmpty()) out = out.replace(secret, MASK)
        }
        return out
    }
}
