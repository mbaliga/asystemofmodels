package xyz.mdhv.asom.vault

/** Ciphertext + nonce pair as persisted (never plaintext, §8). */
class WrappedBlob(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
)

/**
 * Persistence seam for the vault. On device this is Room ([RoomVaultStore]);
 * unit tests use an in-memory impl. Only ever sees ciphertext.
 */
interface VaultStore {
    fun loadWrappedDataKey(): WrappedBlob?
    fun saveWrappedDataKey(blob: WrappedBlob)
    fun loadKey(providerId: String): WrappedBlob?
    fun saveKey(providerId: String, blob: WrappedBlob)
    fun deleteKey(providerId: String)
    fun listProviderIds(): List<String>
}

/**
 * Crypto seam over the Android Keystore master key (AES-256-GCM,
 * StrongBox-when-available). Wraps/unwraps the random data key (§8).
 */
interface WrappingCipher {
    fun wrap(plaintext: ByteArray): WrappedBlob
    fun unwrap(blob: WrappedBlob): ByteArray
}
