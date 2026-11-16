package xyz.mdhv.asom.lab.proto.trust

import java.security.MessageDigest
import xyz.mdhv.asom.lab.json.B64Result
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.manifest.Spki
import xyz.mdhv.asom.lab.manifest.TestOnlyKeys

/** Why an SPKI could not become a pin. The codes are the verifier's own (LAB_SPEC 4.5, 4.6). */
enum class PinReject { ALG_UNSUPPORTED, TEST_ONLY_KEY }

sealed interface PinImport {
    class Ok(val pin: Pin) : PinImport
    class Reject(val code: PinReject) : PinImport
}

/**
 * `pin = SHA-256(DER SubjectPublicKeyInfo of the NIK)`: the only authentication input (trust.md 2.3). A pin is exactly 32 bytes, and the
 * only comparison is [equalsConstantTime] over two whole pins; there is no prefix or tag comparison anywhere.
 */
class Pin private constructor(private val bytes: ByteArray) {
    fun bytes(): ByteArray = bytes.copyOf()

    val nodeId: String get() = Base64Strict.encodeUrlNoPad(bytes)

    val nodeTag: String get() = PinDerivation.nodeTag(bytes)

    val display: String get() = nodeTag.uppercase().chunked(4).joinToString("-")

    fun equalsConstantTime(other: Pin): Boolean = constantTimeEquals(bytes, other.bytes)

    override fun equals(other: Any?): Boolean = other is Pin && equalsConstantTime(other)

    override fun hashCode(): Int = java.util.Arrays.hashCode(bytes)

    override fun toString(): String = "Pin($nodeTag)"

    companion object {
        const val LENGTH = 32

        /** Both arguments must be whole pins: a value of another length is never equal to a pin, whatever its prefix. */
        fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = a.size == LENGTH && b.size == LENGTH && MessageDigest.isEqual(a, b)

        fun ofHash(hash: ByteArray): Pin {
            require(hash.size == LENGTH) { "a pin is ${LENGTH} bytes" }
            return Pin(hash.copyOf())
        }

        /** The pin of a strict 91-byte P-256 SPKI. Anything else is `ALG_UNSUPPORTED`; the TEST-ONLY keys are refused when [productionKeys]. */
        fun fromSpki(spki: ByteArray, productionKeys: Boolean = true): PinImport {
            if (Spki.strict(spki) == null) return PinImport.Reject(PinReject.ALG_UNSUPPORTED)
            val pin = Pin(Spki.pin(spki))
            if (productionKeys && TestOnlyKeys.isTestOnly(pin.nodeId)) return PinImport.Reject(PinReject.TEST_ONLY_KEY)
            return PinImport.Ok(pin)
        }

        /** `nodeId` (43 characters of base64url) back to a pin; null for any other text. */
        fun fromNodeId(nodeId: String): Pin? {
            if (nodeId.length != 43) return null
            val r = Base64Strict.decodeUrlNoPad(nodeId) as? B64Result.Ok ?: return null
            return if (r.bytes.size == LENGTH) Pin(r.bytes) else null
        }
    }
}

/** The display names derived from a pin (trust.md 2.3): `nodeTag` and the grouped fingerprint. Never used for authorisation. */
object PinDerivation {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    fun nodeTag(pin: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (x in pin) {
            buffer = (buffer shl 8) or (x.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString().take(16)
    }
}
