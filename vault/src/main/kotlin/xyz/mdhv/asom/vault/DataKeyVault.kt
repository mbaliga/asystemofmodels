package xyz.mdhv.asom.vault

import java.security.GeneralSecurityException
import java.security.ProviderException
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
    private var unreadable = false

    /**
     * True once the Keystore master key can no longer unwrap the stored data
     * key — the alias was regenerated or the Keystore was reset, which makes
     * every stored key permanently undecryptable. Callers must treat it as
     * "no usable key" rather than letting a crypto exception reach the request
     * path (it would surface as a 400/500 blaming the caller, §5.5).
     */
    val isUnreadable: Boolean get() = synchronized(lock) { unreadable }

    private fun dataKeyOrNull(): ByteArray? = synchronized(lock) {
        if (unreadable) return null
        dataKey?.let { return it }
        val existing = store.loadWrappedDataKey()
        val key = if (existing != null) {
            try {
                wrapper.unwrap(existing)
            } catch (e: GeneralSecurityException) {
                unreadable = true
                return null
            } catch (e: ProviderException) {
                unreadable = true
                return null
            }
        } else {
            ByteArray(32).also { random.nextBytes(it) }
                .also { store.saveWrappedDataKey(wrapper.wrap(it)) }
        }
        dataKey = key
        key
    }

    /** False when the vault is unreadable — the Keys tab must offer [reset]. */
    fun storeKey(providerId: String, key: String): Boolean {
        val dataKey = dataKeyOrNull() ?: return false
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(128, nonce))
        }
        store.saveKey(providerId, WrappedBlob(cipher.doFinal(key.toByteArray(Charsets.UTF_8)), nonce))
        return true
    }

    fun getKey(providerId: String): String? {
        val dataKey = dataKeyOrNull() ?: return null
        val blob = store.loadKey(providerId) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(128, blob.nonce))
            }
            cipher.doFinal(blob.ciphertext).toString(Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    fun deleteKey(providerId: String) = store.deleteKey(providerId)

    fun hasKey(providerId: String): Boolean = !isUnreadable && store.loadKey(providerId) != null

    fun providersWithKeys(): List<String> = if (isUnreadable) emptyList() else store.listProviderIds()

    /**
     * Forces the master-key unwrap so the dashboard can report the vault's
     * real state instead of inferring "key stored" from a row's existence.
     * An empty vault is readable without minting anything.
     */
    fun isReadable(): Boolean = synchronized(lock) {
        if (store.loadWrappedDataKey() == null) true else dataKeyOrNull() != null
    }

    /**
     * The only recovery from an unreadable vault (§8): drop every ciphertext
     * and mint a fresh data key. Keys are re-entered by hand — nothing
     * transfers them programmatically (§10A).
     */
    fun reset() = synchronized(lock) {
        store.clear()
        dataKey = null
        unreadable = false
    }
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
