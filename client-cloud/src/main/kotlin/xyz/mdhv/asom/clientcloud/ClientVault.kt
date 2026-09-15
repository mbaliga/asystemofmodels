package xyz.mdhv.asom.clientcloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * §10A.3: a `CloudOnly` app holds keys in ITS OWN vault, human-entered in
 * that app. Keys never move between surfaces programmatically — asom never
 * emits or ingests one. Deliberately small: Keystore AES-256-GCM master key,
 * ciphertext+nonce in app-private SharedPreferences.
 */
class ClientVault(
    context: Context,
    private val alias: String = "asom-client-cloud-key",
) {
    private val prefs = context.getSharedPreferences("asom-client-cloud-vault", Context.MODE_PRIVATE)

    fun storeKey(providerId: String, key: String) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, masterKey()) }
        val ciphertext = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("ct_$providerId", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString("iv_$providerId", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    /**
     * The Keystore master key is never backed up, so on a host app that leaves
     * Android Auto Backup on (the default — this library cannot set the host's
     * `<application>` backup attributes; see docs/CLIENT_API.md) a restore onto
     * a new device leaves ciphertext that can never decrypt. Drop it and report
     * "no key" rather than throwing out of every call site, all of which are
     * written for a null.
     */
    fun getKey(providerId: String): String? {
        val ct = prefs.getString("ct_$providerId", null) ?: return null
        val iv = prefs.getString("iv_$providerId", null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(
                    Cipher.DECRYPT_MODE, masterKey(),
                    GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
                )
            }
            cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (e: BadPaddingException) {
            deleteKey(providerId)
            null
        } catch (e: IllegalArgumentException) {
            deleteKey(providerId)
            null
        } catch (e: GeneralSecurityException) {
            // Only a bad GCM tag or corrupt Base64 proves the entry is
            // permanently unusable; keep it for a transient Keystore failure.
            null
        }
    }

    /** Agrees with [getKey]: an undecryptable entry is not a stored key. */
    fun hasKey(providerId: String): Boolean = getKey(providerId) != null

    fun deleteKey(providerId: String) {
        prefs.edit().remove("ct_$providerId").remove("iv_$providerId").apply()
    }

    fun providersWithKeys(): List<String> =
        prefs.all.keys.filter { it.startsWith("ct_") }
            .map { it.removePrefix("ct_") }
            .filter { hasKey(it) }
            .sorted()

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
