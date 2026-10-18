package xyz.mdhv.asom.desktop.mac.helper

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The strict JSON profile of helper-protocol/SCHEMA.md section 2. This is its own parser, not `:node-core`'s `StrictJson`,
 * because that one reads `\u` escapes with `Character.digit`, which accepts non-ASCII decimal digits (`\u` followed by
 * Arabic-Indic digits parses), so it does not implement "ASCII hex only" (ERRATA MAC-JSON-1, shown by vector HP-REQX in
 * requests-reject.jsonl). Both lanes (this one and the Swift helper) are pinned by the same vectors.
 */
sealed interface JV {
    class Obj(val members: List<Pair<String, JV>>) : JV {
        operator fun get(key: String): JV? = members.firstOrNull { it.first == key }?.second
    }

    class Arr(val items: List<JV>) : JV
    class Str(val value: String) : JV
    class Num(val value: Long) : JV
    class Bool(val value: Boolean) : JV
    data object Null : JV
}

class JsonProfileException(message: String, val offset: Int) : IllegalArgumentException("$message at offset $offset")

object HelperJson {
    const val MAX_DEPTH = 32
    const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    /** Strict UTF-8: malformed or overlong input is an error, never replaced. */
    fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        throw JsonProfileException("invalid UTF-8", 0)
    }

    fun parse(text: String): JV {
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (p.pos != text.length) throw JsonProfileException("trailing content", p.pos)
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun fail(msg: String): Nothing = throw JsonProfileException(msg, pos)

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
        }

        fun value(depth: Int): JV {
            if (depth > MAX_DEPTH) fail("nesting too deep")
            if (pos >= s.length) fail("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> JV.Str(str())
                't' -> lit("true", JV.Bool(true))
                'f' -> lit("false", JV.Bool(false))
                'n' -> lit("null", JV.Null)
                '-', in '0'..'9' -> num()
                else -> fail("unexpected character '$c'")
            }
        }

        fun lit(word: String, v: JV): JV {
            if (!s.startsWith(word, pos)) fail("bad literal")
            pos += word.length
            return v
        }

        fun isDigit(c: Char) = c in '0'..'9'

        fun num(): JV {
            var negative = false
            if (s[pos] == '-') { negative = true; pos++ }
            if (pos >= s.length || !isDigit(s[pos])) fail("bad number")
            val digitsStart = pos
            if (s[pos] == '0') {
                pos++
                if (pos < s.length && isDigit(s[pos])) fail("leading zero")
            } else {
                while (pos < s.length && isDigit(s[pos])) pos++
            }
            if (pos < s.length && (s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E')) fail("fraction or exponent (integers only)")
            val digits = pos - digitsStart
            if (negative && digits == 1 && s[digitsStart] == '0') fail("negative zero")
            if (digits > 16) fail("integer out of range")
            val n = s.substring(digitsStart, pos).toLong()
            if (n > MAX_SAFE_INTEGER) fail("integer out of range")
            return JV.Num(if (negative) -n else n)
        }

        fun hex4(): Int {
            if (pos + 4 > s.length) fail("bad unicode escape")
            var v = 0
            repeat(4) {
                val c = s[pos]
                val d = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> fail("bad unicode escape")
                }
                v = v * 16 + d
                pos++
            }
            return v
        }

        fun str(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("unterminated string")
                val c = s[pos]
                when {
                    c == '"' -> { pos++; return sb.toString() }
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
                            'u' -> {
                                val u = hex4()
                                if (u in 0xD800..0xDBFF) {
                                    if (pos + 2 > s.length || s[pos] != '\\' || s[pos + 1] != 'u') fail("lone surrogate")
                                    pos += 2
                                    val low = hex4()
                                    if (low !in 0xDC00..0xDFFF) fail("lone surrogate")
                                    sb.append(u.toChar()).append(low.toChar())
                                } else if (u in 0xDC00..0xDFFF) {
                                    fail("lone surrogate")
                                } else {
                                    sb.append(u.toChar())
                                }
                            }
                            else -> { pos--; fail("bad escape '\\$e'") }
                        }
                    }
                    else -> { sb.append(c); pos++ }
                }
            }
        }

        fun obj(depth: Int): JV {
            pos++
            val members = ArrayList<Pair<String, JV>>()
            val seen = HashSet<String>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return JV.Obj(members) }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') fail("object name expected")
                val key = str()
                if (!seen.add(key)) fail("duplicate object name")
                skipWs()
                if (pos >= s.length || s[pos] != ':') fail("':' expected")
                pos++
                skipWs()
                members += key to value(depth + 1)
                skipWs()
                if (pos >= s.length) fail("unterminated object")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return JV.Obj(members)
                    else -> { pos--; fail("',' or '}' expected") }
                }
            }
        }

        fun arr(depth: Int): JV {
            pos++
            val items = ArrayList<JV>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return JV.Arr(items) }
            while (true) {
                skipWs()
                items += value(depth + 1)
                skipWs()
                if (pos >= s.length) fail("unterminated array")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return JV.Arr(items)
                    else -> { pos--; fail("',' or ']' expected") }
                }
            }
        }
    }

    /** Canonical string output (SCHEMA section 2): `"` and `\` escaped, characters below U+0020 as `\u00xx`, all else raw. */
    fun appendString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c < ' ' -> out.append("\\u00").append("0123456789abcdef"[c.code shr 4]).append("0123456789abcdef"[c.code and 15])
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
