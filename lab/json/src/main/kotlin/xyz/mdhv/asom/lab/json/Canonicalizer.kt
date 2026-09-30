package xyz.mdhv.asom.lab.json

/** Lowercase hexadecimal, strict on decode: even length, `[0-9a-f]` only. */
object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (x in bytes) sb.append(DIGITS[(x.toInt() shr 4) and 15]).append(DIGITS[x.toInt() and 15])
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        require(text.length % 2 == 0) { "odd hex length" }
        return ByteArray(text.length / 2) {
            val hi = DIGITS.indexOf(text[it * 2])
            val lo = DIGITS.indexOf(text[it * 2 + 1])
            require(hi >= 0 && lo >= 0) { "not lowercase hex" }
            ((hi shl 4) or lo).toByte()
        }
    }
}

/** `parse` then `JCS`: what a verifier does to check that signed bytes are canonical, and what the M01 vectors pin. */
object Canonicalizer {
    sealed interface Result {
        class Canonical(val value: JValue, val bytes: ByteArray) : Result {
            val text: String get() = String(bytes, Charsets.UTF_8)
        }

        class Rejected(val code: JsonRejectCode) : Result
    }

    fun canonicalize(input: ByteArray): Result = when (val p = StrictJson.parse(input)) {
        is ParseResult.Reject -> Result.Rejected(p.code)
        is ParseResult.Ok -> Result.Canonical(p.value, Jcs.serialize(p.value))
    }
}
