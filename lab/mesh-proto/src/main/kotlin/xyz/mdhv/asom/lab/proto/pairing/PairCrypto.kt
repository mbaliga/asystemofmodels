package xyz.mdhv.asom.lab.proto.pairing

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import xyz.mdhv.asom.lab.proto.trust.Pin

/**
 * Proof, SAS and transcript EXACTLY as trust.md 4.5. `pin_S` is always the pin of the chain TLS presented, never a value from a message,
 * and the nonces are the 32-byte values of `PAIR_HELLO` and `PAIR_CHALLENGE`.
 *
 * ```
 * proof      = HMAC-SHA256(key = secret, msg = "asom-pair-v1/proof" || 0x00 || pin_D || pin_S || nonce_S)
 * SAS        = uint32_be(SHA-256("asom-pair-v1/sas" || 0x00 || pin_D || pin_S || nonce_S || nonce_D)[0..4]) mod 1_000_000
 * transcript = SHA-256("asom-pair-v1/transcript" || 0x00 || pin_D || pin_S || nonce_S || nonce_D)
 * ```
 */
object PairCrypto {
    const val SECRET_LENGTH = 32
    const val NONCE_LENGTH = 32
    private const val PROOF_LABEL = "asom-pair-v1/proof"
    private const val SAS_LABEL = "asom-pair-v1/sas"
    private const val TRANSCRIPT_LABEL = "asom-pair-v1/transcript"

    private fun label(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    private fun cat(vararg parts: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in parts) out.write(p)
        return out.toByteArray()
    }

    private fun checkNonce(n: ByteArray, what: String) = require(n.size == NONCE_LENGTH) { "$what is $NONCE_LENGTH bytes" }

    fun proof(secret: ByteArray, pinD: Pin, pinS: Pin, nonceS: ByteArray): ByteArray {
        require(secret.size == SECRET_LENGTH) { "the window secret is $SECRET_LENGTH bytes" }
        checkNonce(nonceS, "nonce_S")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update(label(PROOF_LABEL))
        mac.update(pinD.bytes())
        mac.update(pinS.bytes())
        mac.update(nonceS)
        return mac.doFinal()
    }

    /** The comparison D makes. A proof of any other length is invalid, never an error. */
    fun proofValid(secret: ByteArray, pinD: Pin, pinS: Pin, nonceS: ByteArray, presented: ByteArray): Boolean =
        presented.size == 32 && MessageDigest.isEqual(proof(secret, pinD, pinS, nonceS), presented)

    private fun sasInput(pinD: Pin, pinS: Pin, nonceS: ByteArray, nonceD: ByteArray, which: String): ByteArray {
        checkNonce(nonceS, "nonce_S")
        checkNonce(nonceD, "nonce_D")
        return cat(label(which), pinD.bytes(), pinS.bytes(), nonceS, nonceD)
    }

    /** The six digits as a number, 0..999999. */
    fun sasNumber(pinD: Pin, pinS: Pin, nonceS: ByteArray, nonceD: ByteArray): Int {
        val h = MessageDigest.getInstance("SHA-256").digest(sasInput(pinD, pinS, nonceS, nonceD, SAS_LABEL))
        val u = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        return (u % 1_000_000L).toInt()
    }

    /** Two groups of three digits, zero-padded: `865 412`. */
    fun sas(pinD: Pin, pinS: Pin, nonceS: ByteArray, nonceD: ByteArray): String {
        val s = String.format(java.util.Locale.ROOT, "%06d", sasNumber(pinD, pinS, nonceS, nonceD))
        return s.substring(0, 3) + " " + s.substring(3)
    }

    fun transcript(pinD: Pin, pinS: Pin, nonceS: ByteArray, nonceD: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(sasInput(pinD, pinS, nonceS, nonceD, TRANSCRIPT_LABEL))

    /** What D and S compare in `PAIR_COMMIT` and `PAIR_COMMIT_ACK`; whole-value constant-time comparison. */
    fun transcriptMatches(expected: ByteArray, presented: ByteArray): Boolean = presented.size == 32 && MessageDigest.isEqual(expected, presented)
}
