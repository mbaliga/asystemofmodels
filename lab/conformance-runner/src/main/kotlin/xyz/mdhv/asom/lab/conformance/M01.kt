package xyz.mdhv.asom.lab.conformance

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mdhv.asom.lab.json.B64Result
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.json.Hex
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.json.JsonRejectCode
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson

/**
 * M01: the strict JSON parser, the JCS integer profile and strict base64 of `:json` (LAB_SPEC 4.2, 4.9). The vector files are
 * read with kotlinx-serialization (allowed for vector files); every normative parse below is the hand-written `:json` code.
 * Evidence label: LAB, oracle: self.
 */
class M01Checker : FamilyChecker("M01") {
    override val requiredLaws: Set<String> =
        setOf("jcs-ok", "jcs-idempotent", "utf16-key-order-trap", "b64-ok", "b64-reject") + JsonRejectCode.entries.map { "reject-${it.name}" }

    override fun observe(v: Vector): Observed = when (val kind = v.input.str("kind")) {
        "canonicalize" -> canonicalize(v)
        "base64Either" -> base64(v, either = true)
        "base64UrlNoPad" -> base64(v, either = false)
        else -> throw LawViolation("unknown M01 kind '$kind'")
    }

    private fun inputBytes(v: Vector): ByteArray {
        val hex = v.input.strOrNull("inputHex")
        val text = v.input.strOrNull("inputText")
        if ((hex == null) == (text == null)) throw LawViolation("exactly one of inputHex and inputText must be given")
        return if (hex != null) Hex.decode(hex) else text!!.toByteArray(Charsets.UTF_8)
    }

    private fun canonicalize(v: Vector): Observed {
        return when (val r = StrictJson.parse(inputBytes(v))) {
            is ParseResult.Reject -> {
                bump("reject-${r.code.name}")
                Observed.Reject(r.code.name)
            }
            is ParseResult.Ok -> {
                val canon = Jcs.serialize(r.value)
                val again = StrictJson.parse(canon) as? ParseResult.Ok ?: throw LawViolation("the JCS output does not parse")
                if (again.value != r.value) throw LawViolation("parse(JCS(v)) != v")
                if (!Jcs.serialize(again.value).contentEquals(canon)) throw LawViolation("JCS is not idempotent")
                bump("jcs-ok")
                bump("jcs-idempotent")
                if (keyOrderTrap(r.value)) bump("utf16-key-order-trap")
                Observed.Ok(buildJsonObject { put("text", String(canon, Charsets.UTF_8)); put("utf8Hex", Hex.encode(canon)) })
            }
        }
    }

    private fun base64(v: Vector, either: Boolean): Observed {
        val text = v.input.str("text")
        return when (val r = if (either) Base64Strict.decodeEither(text) else Base64Strict.decodeUrlNoPad(text)) {
            is B64Result.Reject -> {
                bump("b64-reject")
                Observed.Reject(r.code)
            }
            is B64Result.Ok -> {
                bump("b64-ok")
                Observed.Ok(buildJsonObject { put("hex", JsonPrimitive(Hex.encode(r.bytes))) })
            }
        }
    }

    /** True when some object's members sort differently by UTF-16 code units and by code points, so a code-point sort would fail the vector. */
    private fun keyOrderTrap(v: JValue): Boolean = when (v) {
        is JObject -> {
            val names = v.members.map { it.first }
            val byUnits = names.sortedWith { a, b -> Jcs.compareUtf16Units(a, b) }
            val byPoints = names.sortedWith { a, b -> compareCodePoints(a, b) }
            byUnits != byPoints || v.members.any { keyOrderTrap(it.second) }
        }
        is JArray -> v.items.any { keyOrderTrap(it) }
        else -> false
    }

    private fun compareCodePoints(a: String, b: String): Int {
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        for (k in 0 until minOf(x.size, y.size)) if (x[k] != y[k]) return x[k].compareTo(y[k])
        return x.size.compareTo(y.size)
    }
}
