package xyz.mdhv.asom.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore master key (§8): AES-256-GCM, StrongBox when available,
 * TEE fallback. Wraps the vault's random data key. The master key never
 * leaves hardware.
 *
 * NEEDS-DEVICE-VALIDATION: Keystore behavior is only truly testable on a
 * real device (RedMagic checklist).
 */
class KeystoreWrappingCipher(
    private val alias: String = "asom-master-key",
) : WrappingCipher {

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return generate()
    }

    private fun generate(): SecretKey {
        fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val builder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setIsStrongBoxBacked(true)
            }
            return builder.build()
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        return try {
            generator.init(spec(strongBox = true))
            generator.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            // TEE fallback (§8).
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    override fun wrap(plaintext: ByteArray): WrappedBlob {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, masterKey()) }
        return WrappedBlob(ciphertext = cipher.doFinal(plaintext), nonce = cipher.iv)
    }

    override fun unwrap(blob: WrappedBlob): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, blob.nonce))
        }
        return cipher.doFinal(blob.ciphertext)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
