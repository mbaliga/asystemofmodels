package xyz.mdhv.asom.desktop.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class StrictJsonException(message: String, val offset: Int) : IllegalArgumentException("$message at offset $offset")

/**
 * Integer-profile JSON (design C1) for the node's own config and control frames.
 * Rejected: floats, exponents, `-0`, leading zeros, magnitudes beyond +-(2^53 - 1), duplicate object names,
 * lone surrogates, raw control characters, trailing content, and more than [MAX_DEPTH] nested containers, and `\u` escapes
 * whose digits are not ASCII 0-9, A-F, a-f. The caller decodes bytes to text strictly (see `ControlFrames.read`).
 * The lab's cap is 16 containers (LAB_SPEC 4.2); this profile keeps 32 (desktop ERRATA ERR-FX4-DK-3): config and control frames
 * never leave the machine.
 * (The lab has its own tokenizer; the desktop build cannot depend on the lab, so this is a small separate one.)
 */
object StrictJson {
    const val MAX_DEPTH = 32
    const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    fun parse(text: String): JsonElement {
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (p.pos != text.length) throw StrictJsonException("trailing content", p.pos)
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
        }

        fun fail(msg: String): Nothing = throw StrictJsonException(msg, pos)

        fun value(depth: Int): JsonElement {
            if (pos >= s.length) fail("unexpected end")
            return when (val c = s[pos]) {
                '{' -> { if (depth >= MAX_DEPTH) fail("nesting too deep"); obj(depth) }
                '[' -> { if (depth >= MAX_DEPTH) fail("nesting too deep"); arr(depth) }
                '"' -> JsonPrimitive(str())
                't' -> lit("true", JsonPrimitive(true))
                'f' -> lit("false", JsonPrimitive(false))
                'n' -> lit("null", JsonNull)
                '-', in '0'..'9' -> num()
                else -> fail("unexpected character '$c'")
            }
        }

        fun lit(word: String, v: JsonElement): JsonElement {
            if (!s.startsWith(word, pos)) fail("bad literal")
            pos += word.length
            return v
        }

        fun num(): JsonElement {
            val start = pos
            if (s[pos] == '-') pos++
            if (pos >= s.length || s[pos] !in '0'..'9') fail("bad number")
            if (s[pos] == '0') {
                pos++
                if (pos < s.length && s[pos] in '0'..'9') fail("leading zero")
            } else {
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E')) fail("fraction or exponent (integers only)")
            val text = s.substring(start, pos)
            if (text == "-0") fail("negative zero")
            if (text.trimStart('-').length > 16) fail("integer out of range")
            val n = text.toLong()
            if (n > MAX_SAFE_INTEGER || n < -MAX_SAFE_INTEGER) fail("integer out of range")
            return JsonPrimitive(n)
        }

        fun hex4(): Int {
            if (pos + 4 > s.length) fail("bad unicode escape")
            var v = 0
            repeat(4) {
                val d = when (val h = s[pos]) {
                    in '0'..'9' -> h - '0'
                    in 'a'..'f' -> h - 'a' + 10
                    in 'A'..'F' -> h - 'A' + 10
                    else -> fail("bad unicode escape")
                }
                v = v * 16 + d
                pos++
            }
            return v
        }

        fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("unterminated string")
                val c = s[pos]
                when {
                    c == '"' -> {
                        pos++
                        var i = 0
                        while (i < sb.length) {
                            val ch = sb[i]
                            if (Character.isHighSurrogate(ch)) {
                                if (i + 1 >= sb.length || !Character.isLowSurrogate(sb[i + 1])) fail("lone surrogate")
                                i += 2
                            } else {
                                if (Character.isLowSurrogate(ch)) fail("lone surrogate")
                                i++
                            }
                        }
                        return sb.toString()
                    }
                    c < ' ' -> fail("raw control character in string")
                    c == '\\' -> {
                        pos++
                        if (pos >= s.length) fail("unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> sb.append(hex4().toChar())
                            else -> { pos--; fail("bad escape '\\$e'") }
                        }
                    }
                    else -> { sb.append(c); pos++ }
                }
            }
        }

        fun obj(depth: Int): JsonElement {
            pos++
            val map = LinkedHashMap<String, JsonElement>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return JsonObject(map) }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') fail("object name expected")
                val key = str()
                if (map.containsKey(key)) fail("duplicate object name \"$key\"")
                skipWs()
                if (pos >= s.length || s[pos] != ':') fail("':' expected")
                pos++
                skipWs()
                map[key] = value(depth + 1)
                skipWs()
                if (pos >= s.length) fail("unterminated object")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return JsonObject(map)
                    else -> { pos--; fail("',' or '}' expected") }
                }
            }
        }

        fun arr(depth: Int): JsonElement {
            pos++
            val list = ArrayList<JsonElement>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return JsonArray(list) }
            while (true) {
                skipWs()
                list.add(value(depth + 1))
                skipWs()
                if (pos >= s.length) fail("unterminated array")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return JsonArray(list)
                    else -> { pos--; fail("',' or ']' expected") }
                }
            }
        }
    }
}
