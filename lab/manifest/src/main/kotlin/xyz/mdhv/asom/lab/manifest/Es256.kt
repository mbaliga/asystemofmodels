package xyz.mdhv.asom.lab.manifest

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.X509EncodedKeySpec
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.json.Hex

/** NIST P-256 (secp256r1) constants and the on-curve test of LAB_SPEC 4.5. */
object P256 {
    val P: BigInteger = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    val B: BigInteger = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)

    /** `n = FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551`. */
    val N: BigInteger = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    val HALF_N: BigInteger = N.shiftRight(1)

    val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    }

    /** y^2 = x^3 - 3x + b (mod p), with both coordinates in [0, p). */
    fun onCurve(x: BigInteger, y: BigInteger): Boolean {
        if (x.signum() < 0 || y.signum() < 0 || x >= P || y >= P) return false
        val lhs = y.multiply(y).mod(P)
        val rhs = x.multiply(x).multiply(x).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P)
        return lhs == rhs
    }
}

/** A P-256 key pair as the lab uses it: the private key for signing and the strict 91-byte SPKI for the wire. */
class EcKeyPair(val private: PrivateKey, val spki: ByteArray) {
    val nodeId: String get() = Spki.nodeId(spki)
}

object Spki {
    /** The 26-byte prefix of an uncompressed P-256 SPKI (LAB_SPEC 4.5). */
    val PREFIX: ByteArray = Hex.decode("3059301306072a8648ce3d020106082a8648ce3d030107034200")
    const val LENGTH = 91

    /**
     * The strict wire form: exactly 91 bytes, that prefix, then `04 || x || y`, with the point on the curve. Any other length,
     * prefix, a compressed point or another curve is rejected. Returns the parsed key, or null (the verifier's `ALG_UNSUPPORTED`).
     */
    fun strict(spki: ByteArray): PublicKey? {
        if (spki.size != LENGTH) return null
        for (i in PREFIX.indices) if (spki[i] != PREFIX[i]) return null
        if (spki[PREFIX.size] != 0x04.toByte()) return null
        val x = BigInteger(1, spki.copyOfRange(27, 59))
        val y = BigInteger(1, spki.copyOfRange(59, 91))
        if (!P256.onCurve(x, y)) return null
        return runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) }.getOrNull()
    }

    fun pin(spki: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(spki)

    /** `nodeId`: base64url(pin), no padding, 43 characters. */
    fun nodeId(spki: ByteArray): String = Base64Strict.encodeUrlNoPad(pin(spki))

    private fun base32(b: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (x in b) {
            buffer = (buffer shl 8) or (x.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(alphabet[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(alphabet[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    /** The first 16 characters of lowercase RFC 4648 base32 of the pin (80 bits). Never used for authorisation. */
    fun nodeTag(spki: ByteArray): String = base32(pin(spki)).lowercase().take(16)

    /** `XWWD-3XQW-7TEB-MU27`: the node display fingerprint (pairing, Peers tab, signer line for MESH). */
    fun displayFingerprint(spki: ByteArray): String = nodeTag(spki).uppercase().chunked(4).joinToString("-")

    /** The export fingerprint (FILE context): base32 of the first 16 bytes of the pin, 26 characters, in groups 5-5-4-4-4-4. */
    fun exportFingerprintPlain(spki: ByteArray): String = base32(pin(spki).copyOf(16))

    fun exportFingerprint(spki: ByteArray): String {
        val f = exportFingerprintPlain(spki)
        return listOf(f.substring(0, 5), f.substring(5, 10), f.substring(10, 14), f.substring(14, 18), f.substring(18, 22), f.substring(22, 26)).joinToString("-")
    }

    /**
     * Uppercase the ASCII letters a-z, delete `-` and spaces, nothing else (LAB_SPEC 4.5). Unicode upper-casing is NOT used: it maps U+017F and
     * U+0131 onto base32 letters and U+00DF onto two (ERR-FX-CV6, apple E-09).
     */
    fun normaliseFingerprint(s: String): String {
        val out = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch == '-' || ch == ' ' -> Unit
                ch in 'a'..'z' -> out.append(ch - 32)
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    fun fromPublic(key: PublicKey): ByteArray = key.encoded

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

object Es256 {
    private const val ALG = "SHA256withECDSAinP1363Format"

    fun privateKeyFromScalar(dHex: String): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(dHex, 16), P256.params))

    /** A fresh P-256 key pair from the JDK (a per-export key, LAB_SPEC 4.7). */
    fun generate(): EcKeyPair {
        val kp = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return EcKeyPair(kp.private, kp.public.encoded)
    }

    /** Sign (producer): the JDK emits raw `r||s` (64 bytes); normalise to low-S. */
    fun sign(key: PrivateKey, message: ByteArray): ByteArray {
        val sig = Signature.getInstance(ALG)
        sig.initSign(key)
        sig.update(message)
        val rs = sig.sign()
        check(rs.size == 64) { "the JDK returned ${rs.size} signature bytes" }
        return normaliseLowS(rs)
    }

    /** If s > n/2 then s = n - s; r is unchanged. */
    fun normaliseLowS(rs: ByteArray): ByteArray {
        require(rs.size == 64)
        val s = BigInteger(1, rs.copyOfRange(32, 64))
        if (s <= P256.HALF_N) return rs.copyOf()
        return rs.copyOfRange(0, 32) + fixed32(P256.N.subtract(s))
    }

    /** Before `verify`: 0 < r < n and 0 < s < n. */
    fun rangeOk(rs: ByteArray): Boolean {
        if (rs.size != 64) return false
        val r = BigInteger(1, rs.copyOfRange(0, 32))
        val s = BigInteger(1, rs.copyOfRange(32, 64))
        return r.signum() > 0 && r < P256.N && s.signum() > 0 && s < P256.N
    }

    /** Verify (consumer): high-S is accepted (M02-105). The caller has checked the length and range. */
    fun verify(key: PublicKey, message: ByteArray, rs64: ByteArray): Boolean = try {
        val v = Signature.getInstance(ALG)
        v.initVerify(key)
        v.update(message)
        v.verify(rs64)
    } catch (e: java.security.SignatureException) {
        false
    }

    fun fixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val stripped = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        return ByteArray(32 - stripped.size) + stripped
    }
}

/** The DER <-> raw codec for platforms whose APIs return DER. A DER signature is never accepted on the wire (M03-102). */
object SigCodec {
    sealed interface Result {
        class Ok(val bytes: ByteArray) : Result

        /** Always `SIGNATURE_ENCODING`. */
        class Reject(val why: String) : Result {
            val code: String get() = "SIGNATURE_ENCODING"
        }
    }

    /** Strict DER only: definite short or long lengths in minimal form, minimal positive INTEGERs, nothing after the sequence. */
    fun derToRaw(der: ByteArray): Result {
        var i = 0
        fun byte(k: Int): Int = der[k].toInt() and 0xFF
        fun len(): Int? {
            if (i >= der.size) return null
            val first = byte(i++)
            if (first < 0x80) return first
            val n = first and 0x7F
            if (n == 0 || n > 2 || i + n > der.size) return null
            var v = 0
            for (k in 0 until n) v = (v shl 8) or byte(i++)
            if (n == 1 && v < 0x80) return null
            if (n == 2 && v < 0x100) return null
            return v
        }
        if (der.size < 8 || byte(i++) != 0x30) return Result.Reject("not a SEQUENCE")
        val seqLen = len() ?: return Result.Reject("bad or non-minimal sequence length")
        if (i + seqLen != der.size) return Result.Reject("sequence length does not cover the input")
        val parts = ArrayList<BigInteger>(2)
        repeat(2) {
            if (i >= der.size || byte(i++) != 0x02) return Result.Reject("not an INTEGER")
            val l = len() ?: return Result.Reject("bad or non-minimal integer length")
            if (l < 1 || i + l > der.size) return Result.Reject("integer length out of range")
            val body = der.copyOfRange(i, i + l)
            i += l
            if (body[0].toInt() and 0x80 != 0) return Result.Reject("negative integer")
            if (body.size > 1 && body[0] == 0.toByte() && body[1].toInt() and 0x80 == 0) return Result.Reject("non-minimal integer")
            val v = BigInteger(1, body)
            if (v.bitLength() > 256) return Result.Reject("integer wider than 32 bytes")
            parts += v
        }
        if (i != der.size) return Result.Reject("trailing bytes")
        return Result.Ok(Es256.fixed32(parts[0]) + Es256.fixed32(parts[1]))
    }

    /** Raw `r||s` (exactly 64 bytes) to minimal DER. */
    fun rawToDer(raw: ByteArray): Result {
        if (raw.size != 64) return Result.Reject("raw signature is ${raw.size} bytes, expected 64")
        fun integer(b: ByteArray): ByteArray {
            var k = 0
            while (k < b.size - 1 && b[k] == 0.toByte()) k++
            var body = b.copyOfRange(k, b.size)
            if (body[0].toInt() and 0x80 != 0) body = byteArrayOf(0) + body
            return byteArrayOf(0x02, body.size.toByte()) + body
        }
        val inner = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
        val header = if (inner.size < 0x80) byteArrayOf(0x30, inner.size.toByte()) else byteArrayOf(0x30, 0x81.toByte(), inner.size.toByte())
        return Result.Ok(header + inner)
    }

    /** M01-204: the low-S normalisation as a codec operation on a raw signature. */
    fun normaliseRaw(raw: ByteArray): Result = if (raw.size != 64) Result.Reject("raw signature is ${raw.size} bytes, expected 64") else Result.Ok(Es256.normaliseLowS(raw))
}
