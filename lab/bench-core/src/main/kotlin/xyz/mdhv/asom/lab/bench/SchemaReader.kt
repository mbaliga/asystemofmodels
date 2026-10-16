package xyz.mdhv.asom.lab.bench

import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue

/** A typed decoder found a value outside the schema. The verifier maps it to `SCHEMA_INVALID`. */
class SchemaViolation(val path: String, val why: String) : Exception("$path: $why")

/** Members forbidden at any depth of a signed body (LAB_SPEC 4.1 P7). */
val FORBIDDEN_MEMBER_NAMES: Set<String> = setOf("derived", "render", "textSha256", "field", "custom")

/** Finds a forbidden member name at any depth of [v]; returns its path or null. */
fun findForbiddenMember(v: JValue, path: String = "$"): String? = when (v) {
    is JObject -> v.members.firstNotNullOfOrNull { (k, x) ->
        if (k in FORBIDDEN_MEMBER_NAMES) "$path.$k" else findForbiddenMember(x, "$path.$k")
    }
    is JArray -> v.items.withIndex().firstNotNullOfOrNull { (i, x) -> findForbiddenMember(x, "$path[$i]") }
    else -> null
}

/** Shared decode state: whether unknown members are tolerated (a `schemaMinor` above the known one) and how many were seen. */
class RdContext(val tolerateUnknown: Boolean) {
    var unknown: Int = 0
}

/** String shapes of the manifest schema (`asom.manifest.1.schema.json` `$defs`). Lengths are counted in code points (LAB_SPEC 4.6 step 11). */
enum class StrRule {
    /** 1 to 96 code points, none of the control, bidi or line-separator characters of manifest.md 4.2. */
    TEXT,
    ID,
    METHOD_ID,
    SEMVER,
    B64U32,
    SHA256_HEX,
    DATE,
    COMMIT,
    QUANT,
    ANY_SHORT,
}

object Rules {
    private val id = Regex("[a-z0-9][a-z0-9._+-]{0,63}")
    private val methodId = Regex("[a-z0-9][a-z0-9.-]{0,47}/[0-9]{1,4}")
    private val semver = Regex("(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})")
    private val b64u32 = Regex("[A-Za-z0-9_-]{43}")
    private val sha256Hex = Regex("[0-9a-f]{64}")
    private val date = Regex("20[2-9][0-9]-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])")
    private val commit = Regex("[0-9a-f]{7,40}")
    private val quant = Regex("[A-Za-z0-9_.-]{1,24}")

    fun codePoints(s: String): Int = s.codePointCount(0, s.length)

    fun isBadTextChar(c: Char): Boolean {
        val x = c.code
        return x <= 0x1F || x in 0x7F..0x9F || x == 0x061C || x == 0x200E || x == 0x200F || x == 0x2028 || x == 0x2029 ||
            x in 0x202A..0x202E || x in 0x2066..0x2069 || x == 0xFEFF
    }

    fun matches(rule: StrRule, s: String): Boolean = when (rule) {
        StrRule.TEXT -> codePoints(s) in 1..96 && s.none { isBadTextChar(it) }
        StrRule.ID -> id.matches(s)
        StrRule.METHOD_ID -> methodId.matches(s)
        StrRule.SEMVER -> semver.matches(s)
        StrRule.B64U32 -> b64u32.matches(s)
        StrRule.SHA256_HEX -> sha256Hex.matches(s)
        StrRule.DATE -> date.matches(s)
        StrRule.COMMIT -> commit.matches(s)
        StrRule.QUANT -> quant.matches(s)
        StrRule.ANY_SHORT -> codePoints(s) in 1..256
    }

    /** Validates a `YYYY-MM-DD` string as a real calendar date (the schema pattern allows 2026-02-31). */
    fun isRealDate(s: String): Boolean = runCatching { java.time.LocalDate.parse(s) }.isSuccess
}

/**
 * A hand-written reader over a [JObject]: every read names the member, checks its type and bounds, and remembers the name,
 * so that [finish] can count (or reject) the members nobody asked about. This is the only way a typed decoder reads a signed
 * document; JSON Schema is documentation and an oracle only (LAB_SPEC 4.1).
 */
class Rd private constructor(private val o: JObject, val path: String, private val ctx: RdContext) {
    private val seen = HashSet<String>()

    companion object {
        fun root(v: JValue, path: String, ctx: RdContext): Rd {
            val o = v as? JObject ?: throw SchemaViolation(path, "not an object")
            return Rd(o, path, ctx)
        }
    }

    fun has(name: String): Boolean = o[name] != null

    private fun get(name: String): JValue? {
        seen += name
        return o[name]
    }

    private fun req(name: String): JValue = get(name) ?: throw SchemaViolation("$path.$name", "missing")

    private fun bad(name: String, why: String): Nothing = throw SchemaViolation("$path.$name", why)

    fun obj(name: String): Rd = Rd(req(name) as? JObject ?: bad(name, "not an object"), "$path.$name", ctx)

    fun objOrNull(name: String): Rd? = when (val v = req(name)) {
        is JNull -> null
        is JObject -> Rd(v, "$path.$name", ctx)
        else -> bad(name, "not an object or null")
    }

    fun optObj(name: String): Rd? = when (val v = get(name)) {
        null -> null
        is JObject -> Rd(v, "$path.$name", ctx)
        else -> bad(name, "not an object")
    }

    fun raw(name: String): JValue = req(name)

    fun str(name: String, rule: StrRule): String {
        val s = (req(name) as? JString ?: bad(name, "not a string")).value
        if (!Rules.matches(rule, s)) bad(name, "string violates $rule")
        return s
    }

    fun strOrNull(name: String, rule: StrRule): String? = when (val v = req(name)) {
        is JNull -> null
        is JString -> v.value.also { if (!Rules.matches(rule, it)) bad(name, "string violates $rule") }
        else -> bad(name, "not a string or null")
    }

    fun optStr(name: String, rule: StrRule): String? = when (val v = get(name)) {
        null -> null
        is JString -> v.value.also { if (!Rules.matches(rule, it)) bad(name, "string violates $rule") }
        else -> bad(name, "not a string")
    }

    fun enum(name: String, allowed: Set<String>): String {
        val s = (req(name) as? JString ?: bad(name, "not a string")).value
        if (s !in allowed) bad(name, "'$s' is not one of $allowed")
        return s
    }

    fun int(name: String, lo: Long, hi: Long): Long {
        val n = (req(name) as? JInt ?: bad(name, "not an integer")).value
        if (n < lo || n > hi) bad(name, "$n is outside $lo..$hi")
        return n
    }

    fun intOrNull(name: String, lo: Long, hi: Long): Long? = when (val v = req(name)) {
        is JNull -> null
        is JInt -> v.value.also { if (it < lo || it > hi) bad(name, "$it is outside $lo..$hi") }
        else -> bad(name, "not an integer or null")
    }

    fun optInt(name: String, lo: Long, hi: Long): Long? = when (val v = get(name)) {
        null -> null
        is JInt -> v.value.also { if (it < lo || it > hi) bad(name, "$it is outside $lo..$hi") }
        else -> bad(name, "not an integer")
    }

    fun bool(name: String): Boolean = (req(name) as? JBool ?: bad(name, "not a boolean")).value

    fun boolOrNull(name: String): Boolean? = when (val v = req(name)) {
        is JNull -> null
        is JBool -> v.value
        else -> bad(name, "not a boolean or null")
    }

    fun requireNull(name: String) {
        if (req(name) !is JNull) bad(name, "must be null in this profile")
    }

    fun arrRaw(name: String, min: Int, max: Int): List<JValue> {
        val a = req(name) as? JArray ?: bad(name, "not an array")
        if (a.items.size < min || a.items.size > max) bad(name, "${a.items.size} items, expected $min..$max")
        return a.items
    }

    fun arrObjs(name: String, min: Int, max: Int): List<Rd> =
        arrRaw(name, min, max).mapIndexed { i, v -> Rd(v as? JObject ?: bad("$name[$i]", "not an object"), "$path.$name[$i]", ctx) }

    fun arrStrs(name: String, min: Int, max: Int, rule: StrRule, unique: Boolean = false): List<String> {
        val items = arrRaw(name, min, max).mapIndexed { i, v ->
            val s = (v as? JString ?: bad("$name[$i]", "not a string")).value
            if (!Rules.matches(rule, s)) bad("$name[$i]", "string violates $rule")
            s
        }
        if (unique && items.toSet().size != items.size) bad(name, "items are not unique")
        return items
    }

    fun arrInts(name: String, min: Int, max: Int, lo: Long, hi: Long): List<Long> = arrRaw(name, min, max).mapIndexed { i, v ->
        val n = (v as? JInt ?: bad("$name[$i]", "not an integer")).value
        if (n < lo || n > hi) bad("$name[$i]", "$n is outside $lo..$hi")
        n
    }

    /** Counts members nobody read: tolerated only when the context says so (a `schemaMinor` above the known one). */
    fun finish() {
        val extra = o.members.count { it.first !in seen }
        if (extra > 0) {
            if (!ctx.tolerateUnknown) throw SchemaViolation(path, "unknown member(s): ${o.members.map { it.first }.filter { it !in seen }}")
            ctx.unknown += extra
        }
    }
}

inline fun <T> Rd.scope(block: Rd.() -> T): T {
    val r = block()
    finish()
    return r
}
