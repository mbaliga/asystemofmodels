package xyz.mdhv.asom.desktop.win.keys

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import xyz.mdhv.asom.desktop.KeyStorage

/**
 * The node identity key tiers on Windows (windows.md 5) and what each one does NOT guarantee. The disclosure strings are
 * shown by `asom doctor` and pinned by a test, so a tier can never be presented without its limits.
 */
enum class NikTier(
    val id: String,
    val keyStorage: KeyStorage,
    /** `HELLO.keyTier` and the Peers tab. T2 is ALWAYS "self-reported": no attestation exists (it would need an online CA path, a new egress). */
    val label: String,
    val doesNotGuarantee: List<String>,
) {
    T2_TPM(
        "T2", KeyStorage.TPM, "hardware-backed (self-reported)",
        listOf(
            "USE of the key by any code running as the owner (user mode), as NT SERVICE\\asom, as an Administrator or as SYSTEM while the machine runs",
            "attestation: none, because TPM key attestation needs a manufacturer CA path and online checks, which would be a new egress",
            "survival: clearing the TPM or some firmware updates destroy the key, and the node must then be re-paired with every peer",
            "ECDSA P-256 support on this TPM (assumption AW01, spike S-W1)",
        ),
    ),
    T1_OS_KEYSTORE(
        "T1", KeyStorage.OS_KEYSTORE, "os-keystore",
        listOf(
            "protection from same-user malware USING the key",
            "protection from an Administrator or SYSTEM EXTRACTING it: non-exportable is a policy flag, not hardware",
            "protection on a roaming profile: user keys live in the roaming profile and can appear on another machine",
        ),
    ),
    T0_FILE(
        "T0", KeyStorage.FILE, "file",
        listOf(
            "protection from same-user code, an Administrator, backups, Volume Shadow Copy and System Restore snapshots",
            "more than the user's logon credential when the file is DPAPI-wrapped (and the wrap roams with a roaming profile)",
            "any use outside CI and headless tests: T0 is selected only by an explicit --key-tier file",
        ),
    ),
}

/** The node identity key as the node uses it: it signs, it names its public half, it can be destroyed. */
interface NikKey : AutoCloseable {
    val tier: NikTier

    /** ES256 over [message]: SHA-256, then ECDSA P-256, in the 64-byte r||s form (never DER). */
    fun sign(message: ByteArray): ByteArray

    /** DER SubjectPublicKeyInfo of the public key. */
    fun spki(): ByteArray

    /** Irreversible: the node must be re-paired everywhere afterwards. */
    fun delete()
}

/** `BCRYPT_ECCPUBLIC_BLOB` for ECDSA P-256 (`BCRYPT_ECDSA_PUBLIC_P256_MAGIC` "ECS1", cbKey 32, X, Y) to SPKI. */
object EccBlob {
    private const val ECDSA_PUBLIC_P256_MAGIC = 0x31534345
    private const val KEY_BYTES = 32

    /** X||Y, 64 bytes. Any other magic (an ECDH blob, another curve) or length is rejected. */
    fun parseP256(blob: ByteArray): ByteArray {
        require(blob.size == 8 + 2 * KEY_BYTES) { "ECC public blob has ${blob.size} bytes, expected 72" }
        val magic = le32(blob, 0)
        val cb = le32(blob, 4)
        require(magic == ECDSA_PUBLIC_P256_MAGIC) { "not an ECDSA P-256 public blob (magic 0x${Integer.toHexString(magic)})" }
        require(cb == KEY_BYTES) { "cbKey is $cb, expected 32" }
        return blob.copyOfRange(8, 8 + 2 * KEY_BYTES)
    }

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
}

object Spki {
    /** SEQ { SEQ { OID id-ecPublicKey, OID prime256v1 }, BIT STRING { 0x04 || X || Y } }. */
    private val P256_PREFIX = byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01,
        0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00, 0x04,
    )

    fun p256(xy: ByteArray): ByteArray {
        require(xy.size == 64) { "X||Y must be 64 bytes" }
        return P256_PREFIX + xy
    }
}

/** ES256 helpers over the JCA. `SHA256withECDSAinP1363Format` takes and returns the raw r||s form. */
object Es256 {
    const val SIGNATURE_BYTES = 64

    fun sha256(message: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(message)

    fun verify(spki: ByteArray, message: ByteArray, rawSignature: ByteArray): Boolean {
        if (rawSignature.size != SIGNATURE_BYTES) return false
        return try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) as ECPublicKey
            if (key.params.curve.field.fieldSize != 256) return false
            val s = Signature.getInstance("SHA256withECDSAinP1363Format")
            s.initVerify(key)
            s.update(message)
            s.verify(rawSignature)
        } catch (_: Exception) {
            false
        }
    }
}
