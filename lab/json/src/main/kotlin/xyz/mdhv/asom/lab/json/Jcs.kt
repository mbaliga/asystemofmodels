package xyz.mdhv.asom.lab.json

/** The JCS integer profile (RFC 8785 restricted to integers, LAB_SPEC 4.2): the canonical bytes of a [JValue]. */
object Jcs {
    /** Throws [IllegalArgumentException] when the value nests deeper than [StrictJson.MAX_DEPTH], because the parser would reject the result. */
    fun serialize(value: JValue): ByteArray = serializeToString(value).toByteArray(Charsets.UTF_8)

    fun serializeToString(value: JValue): String {
        val sb = StringBuilder()
        write(sb, value, 0)
        return sb.toString()
    }

    /** Unsigned comparison of UTF-16 code units, the RFC 8785 key order. Not a code-point comparison. */
    fun compareUtf16Units(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        for (k in 0 until n) {
            val d = a[k].code - b[k].code
            if (d != 0) return d
        }
        return a.length - b.length
    }

    private fun write(sb: StringBuilder, v: JValue, depth: Int) {
        when (v) {
            is JNull -> sb.append("null")
            is JBool -> sb.append(if (v.value) "true" else "false")
            is JInt -> sb.append(v.value)
            is JString -> string(sb, v.value)
            is JArray -> {
                require(depth < StrictJson.MAX_DEPTH) { "nesting deeper than ${StrictJson.MAX_DEPTH} is outside the profile" }
                sb.append('[')
                v.items.forEachIndexed { k, item ->
                    if (k > 0) sb.append(',')
                    write(sb, item, depth + 1)
                }
                sb.append(']')
            }
            is JObject -> {
                require(depth < StrictJson.MAX_DEPTH) { "nesting deeper than ${StrictJson.MAX_DEPTH} is outside the profile" }
                sb.append('{')
                v.members.sortedWith { x, y -> compareUtf16Units(x.first, y.first) }.forEachIndexed { k, (name, item) ->
                    if (k > 0) sb.append(',')
                    string(sb, name)
                    sb.append(':')
                    write(sb, item, depth + 1)
                }
                sb.append('}')
            }
        }
    }

    private const val HEX = "0123456789abcdef"

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\t' -> sb.append("\\t")
                c == '\n' -> sb.append("\\n")
                c == '\u000C' -> sb.append("\\f")
                c == '\r' -> sb.append("\\r")
                c.code < 0x20 -> sb.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 15])
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
