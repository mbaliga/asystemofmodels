package xyz.mdhv.asom.lab.bench

import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue

/**
 * A deterministic pretty printer for vector files (two-space indent, LF, members in the given order, one trailing newline, UTF-8 when
 * written). Vector files are not signed documents, so the JCS profile does not apply; this exists so that generators need no other JSON library.
 */
object JsonText {
    fun pretty(v: JValue): String = StringBuilder().also { write(it, v, 0) }.append('\n').toString()

    private fun indent(sb: StringBuilder, n: Int) {
        repeat(n) { sb.append("  ") }
    }

    private fun write(sb: StringBuilder, v: JValue, level: Int) {
        when (v) {
            is JNull -> sb.append("null")
            is JBool -> sb.append(v.value)
            is JInt -> sb.append(v.value)
            is JString -> str(sb, v.value)
            is JArray -> {
                if (v.items.isEmpty()) {
                    sb.append("[]")
                } else if (v.items.all { it is JInt || it is JNull || it is JBool } && v.items.size <= 24) {
                    sb.append('[')
                    v.items.forEachIndexed { i, x ->
                        if (i > 0) sb.append(", ")
                        write(sb, x, level)
                    }
                    sb.append(']')
                } else {
                    sb.append("[\n")
                    v.items.forEachIndexed { i, x ->
                        indent(sb, level + 1)
                        write(sb, x, level + 1)
                        if (i < v.items.size - 1) sb.append(',')
                        sb.append('\n')
                    }
                    indent(sb, level)
                    sb.append(']')
                }
            }
            is JObject -> {
                if (v.members.isEmpty()) {
                    sb.append("{}")
                } else {
                    sb.append("{\n")
                    v.members.forEachIndexed { i, (k, x) ->
                        indent(sb, level + 1)
                        str(sb, k)
                        sb.append(": ")
                        write(sb, x, level + 1)
                        if (i < v.members.size - 1) sb.append(',')
                        sb.append('\n')
                    }
                    indent(sb, level)
                    sb.append('}')
                }
            }
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
