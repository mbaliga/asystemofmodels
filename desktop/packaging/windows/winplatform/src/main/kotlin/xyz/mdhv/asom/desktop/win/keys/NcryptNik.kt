package xyz.mdhv.asom.desktop.win.keys

import xyz.mdhv.asom.desktop.win.api.CngKeyHandle
import xyz.mdhv.asom.desktop.win.api.CngPort
import xyz.mdhv.asom.desktop.win.api.CngProvider
import xyz.mdhv.asom.desktop.win.api.KeyScope

/**
 * A NIK held by CNG: T2 through the Microsoft Platform Crypto Provider, T1 through the Microsoft Software KSP. The key
 * is a persisted, non-exportable ECDSA P-256 signing key. Signatures come from `NCryptSignHash` over the SHA-256 digest
 * and are the raw r||s form (AW02), which the tests verify with the JCA.
 *
 * NOTHING here proves that a Platform Crypto Provider key is in a TPM: that is the provider's claim [FW15], not an
 * attestation, and it is always presented as "(self-reported)".
 */
class NcryptNik private constructor(
    override val tier: NikTier,
    private val handle: CngKeyHandle,
) : NikKey {
    private val spki: ByteArray = Spki.p256(EccBlob.parseP256(handle.exportPublicBlob()))

    override fun sign(message: ByteArray): ByteArray {
        val sig = handle.signHash(Es256.sha256(message))
        check(sig.size == Es256.SIGNATURE_BYTES) { "NCryptSignHash returned ${sig.size} bytes, expected 64 (r||s); the wire form would be wrong" }
        return sig
    }

    override fun spki(): ByteArray = spki.copyOf()

    override fun delete() = handle.delete()

    override fun close() = handle.close()

    companion object {
        const val USER_KEY_NAME = "asom-nik-v1"
        const val SERVICE_KEY_NAME = "asom-nik-v1-svc"

        fun providerFor(tier: NikTier): CngProvider = when (tier) {
            NikTier.T2_TPM -> CngProvider.PLATFORM
            NikTier.T1_OS_KEYSTORE -> CngProvider.SOFTWARE
            NikTier.T0_FILE -> throw IllegalArgumentException("T0 is not a CNG tier")
        }

        /** A key that was created but cannot be used (an unexpected blob) is deleted again, never left persisted. */
        fun create(port: CngPort, tier: NikTier, name: String, scope: KeyScope, sddl: String? = null): NcryptNik {
            val h = port.createEcdsaP256(providerFor(tier), name, scope, sddl)
            return try {
                NcryptNik(tier, h)
            } catch (e: Throwable) {
                runCatching { h.delete() }
                throw e
            }
        }

        /** Null when the key does not exist under that provider. */
        fun open(port: CngPort, tier: NikTier, name: String, scope: KeyScope): NcryptNik? {
            val h = port.open(providerFor(tier), name, scope) ?: return null
            return try {
                NcryptNik(tier, h)
            } catch (e: Throwable) {
                runCatching { h.close() }
                throw e
            }
        }
    }
}
