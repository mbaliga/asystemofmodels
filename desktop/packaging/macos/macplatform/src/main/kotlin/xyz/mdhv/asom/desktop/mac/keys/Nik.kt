package xyz.mdhv.asom.desktop.mac.keys

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import xyz.mdhv.asom.desktop.KeyStorage

/**
 * The node identity key tiers on macOS (macos.md 5) and what each one does NOT guarantee. The disclosure strings are shown by
 * `asom doctor` and pinned by a test, so a tier can never be shown without its limits. There is no T1: the login keychain is
 * REJECTED on macOS ([REJECTED_LOGIN_KEYCHAIN]).
 */
enum class NikTier(
    val id: String,
    val keyStorage: KeyStorage,
    /** `HELLO.keyTier` and the Peers tab. T2 is ALWAYS "self-reported": no attestation exists (App Attest is rejected, manifest.md 5.5). */
    val label: String,
    val guarantees: List<String>,
    val doesNotGuarantee: List<String>,
) {
    T2_SECURE_ENCLAVE(
        "T2", KeyStorage.SECURE_ENCLAVE, "hardware-backed (self-reported)",
        listOf(
            "the private key never exists outside the Secure Enclave: it cannot be extracted, and the blob is useless on another Mac",
            "offline disk theft does not yield the key (with FileVault)",
        ),
        listOf(
            "USE of the key on this Mac by any process that obtains the blob: the blob is not bound to asom's App ID or Team ID (FM15); " +
                "what keeps other processes from it is the Team-ID app group container, an operating-system policy, not hardware",
            "protection from root, from apps the user granted Full Disk Access, from a user who approves access in Privacy & Security, " +
                "or from code injected into the node itself, all of which can sign as the node while the Mac runs",
            "attestation: none, so peers see \"hardware-backed (self-reported)\"",
            "survival: an erase or restore, or a logic-board swap, destroys the key, and the node must then be paired again on every peer",
        ),
    ),
    T0_FILE(
        "T0", KeyStorage.FILE, "file",
        listOf(
            "other unprivileged users cannot read it (mode 0600 in a 0700 directory)",
            "in a Team-ID group container (a signed build only): the container is protected by SIP on macOS 15 and 26, and macOS 27 denies other developer teams' processes by default (whether that covers every kind of process is AM08, unverified). An UNSIGNED build uses the dev state directory and has none of this",
        ),
        listOf(
            "everything the Secure Enclave tier does not guarantee, plus EXTRACTION and silent cloning: a copied file is the node",
            "protection from Migration Assistant or a restored backup, which copy the file (the backup exclusion and the platform binding only turn an honest clone into a dead identity, and do nothing against a thief who controls the new Mac)",
            "any use outside a Mac without a Secure Enclave, a failed Enclave self-test, CI, or the explicit --key-tier file",
        ),
    ),
    ;

    companion object {
        /** Why there is no T1. Shown by `asom doctor`. */
        const val REJECTED_LOGIN_KEYCHAIN =
            "The login keychain is rejected as a key tier on macOS: the file-based keychain is on the road to deprecation, the data protection " +
                "keychain needs entitlements authorised by a provisioning profile and a user context (so it is unavailable to a daemon), the " +
                "`security` command line route puts the key on a command line and leaves an item that trusts /usr/bin/security (any same-user " +
                "process can read it silently, AM14), and the JDK keychain store returns the key into JVM memory."
    }
}

/** The node identity key as the node uses it: it signs, it names its public half, it can be destroyed. */
interface NikKey : AutoCloseable {
    val tier: NikTier

    /** ES256 over [message]: SHA-256, then ECDSA P-256, in the 64-byte r||s form (never DER). */
    fun sign(message: ByteArray): ByteArray

    /** DER SubjectPublicKeyInfo of the public key (91 bytes). */
    fun spki(): ByteArray

    /** Irreversible: the node must be paired again on every peer afterwards. */
    fun delete()
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

    /** True when [spki] is a well-formed P-256 SubjectPublicKeyInfo whose point is on the curve (the JCA checks that on parse). */
    fun isP256Spki(spki: ByteArray): Boolean = try {
        spki.size == 91 && (KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) as ECPublicKey).params.curve.field.fieldSize == 256
    } catch (_: Exception) {
        false
    }
}
