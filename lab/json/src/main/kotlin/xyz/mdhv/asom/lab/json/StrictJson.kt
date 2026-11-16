package xyz.mdhv.asom.lab.json

import java.util.BitSet

/** The reject codes of the strict parser, in the order of the LAB_SPEC 4.2 table (the declaration order is the check order). */
enum class JsonRejectCode {
    MALFORMED_JSON,
    INVALID_UNICODE,
    NON_INTEGER_NUMBER,
    NUMBER_RANGE,
    DUPLICATE_KEY,
    TRAILING_DATA,
}

sealed interface ParseResult {
    class Ok(val value: JValue) : ParseResult

    /** [offset] is a byte offset for diagnostics only; it never takes part in a verdict. */
    class Reject(val code: JsonRejectCode, val offset: Int, val detail: String) : ParseResult {
        override fun toString(): String = "Reject($code at $offset: $detail)"
    }
}

/**
 * The hand-written strict parser of LAB_SPEC 4.2. Input is raw bytes.
 *
 * The code returned is that of the FIRST row of the spec table whose condition holds for the input, so the answer never
 * depends on where in the input each condition happens to sit. The rows are, in order: (1) a BOM, or bytes that are not
 * one JSON value; (2) invalid UTF-8 anywhere in the input; (3) a `\u` escape producing a lone surrogate; (4) a number
 * that is not `-?(0|[1-9][0-9]*)` or is `-0`; (5) an integer outside +-(2^53-1); (6) a duplicate member name; (7) nesting
 * deeper than [MAX_DEPTH]; (8) non-whitespace after the value. The scan is iterative, so depth cannot overflow the stack.
 *
 * Nothing is built for a container nested deeper than [MAX_DEPTH]: those levels are skimmed with every check of the normal
 * path but a few bytes of state per level, so a hostile frame of brackets cannot multiply its own size in memory.
 */
object StrictJson {
    const val MAX_DEPTH: Int = 16

    fun parse(bytes: ByteArray): ParseResult {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return ParseResult.Reject(JsonRejectCode.MALFORMED_JSON, 0, "byte order mark")
        }
        val scan = Scan(bytes)
        val value = try {
            scan.document()
        } catch (e: SyntaxError) {
            return ParseResult.Reject(JsonRejectCode.MALFORMED_JSON, e.offset, e.message ?: "syntax error")
        }
        val badUtf8 = Utf8Strict.firstInvalid(bytes)
        if (badUtf8 >= 0) return ParseResult.Reject(JsonRejectCode.INVALID_UNICODE, badUtf8, "invalid UTF-8")
        if (scan.lone >= 0) return ParseResult.Reject(JsonRejectCode.INVALID_UNICODE, scan.lone, "lone surrogate escape")
        if (scan.nonInteger >= 0) return ParseResult.Reject(JsonRejectCode.NON_INTEGER_NUMBER, scan.nonInteger, "not -?(0|[1-9][0-9]*), or -0")
        if (scan.range >= 0) return ParseResult.Reject(JsonRejectCode.NUMBER_RANGE, scan.range, "integer outside +-(2^53-1)")
        if (scan.duplicate >= 0) return ParseResult.Reject(JsonRejectCode.DUPLICATE_KEY, scan.duplicate, "duplicate member name")
        if (scan.tooDeep >= 0) return ParseResult.Reject(JsonRejectCode.MALFORMED_JSON, scan.tooDeep, "nesting deeper than $MAX_DEPTH")
        if (scan.trailing >= 0) return ParseResult.Reject(JsonRejectCode.TRAILING_DATA, scan.trailing, "data after the value")
        return ParseResult.Ok(value)
    }

    private class SyntaxError(val offset: Int, message: String) : RuntimeException(message, null, false, false)

    private sealed class Frame
    private class ObjFrame : Frame() {
        val members = ArrayList<Pair<String, JValue>>()
        val names = HashSet<String>()
        var key: String = ""
    }
    private class ArrFrame : Frame() {
        val items = ArrayList<JValue>()
    }

    private class Scan(private val b: ByteArray) {
        var i = 0
        var lone = -1
        var nonInteger = -1
        var range = -1
        var duplicate = -1
        var tooDeep = -1
        var trailing = -1
        private val stack = ArrayList<Frame>()

        fun document(): JValue {
            var v: JValue? = null
            while (true) {
                if (v == null) {
                    ws()
                    v = begin()
                    if (v == null) continue
                }
                when (val top = stack.lastOrNull()) {
                    null -> {
                        ws()
                        if (i < b.size) trailing = i
                        return v
                    }
                    is ArrFrame -> {
                        top.items.add(v)
                        ws()
                        when (peek()) {
                            ','.code -> { i++; v = null }
                            ']'.code -> { i++; v = JArray(top.items); stack.removeAt(stack.size - 1) }
                            else -> fail("expected ',' or ']'")
                        }
                    }
                    is ObjFrame -> {
                        if (top.names.add(top.key)) top.members.add(top.key to v) else if (duplicate < 0) duplicate = i
                        ws()
                        when (peek()) {
                            ','.code -> { i++; ws(); key(top); v = null }
                            '}'.code -> { i++; v = JObject(top.members); stack.removeAt(stack.size - 1) }
                            else -> fail("expected ',' or '}'")
                        }
                    }
                }
            }
        }

        /** Reads a scalar or an empty container and returns it; for a non-empty container it opens a frame and returns null. */
        private fun begin(): JValue? {
            when (val c = peek()) {
                '{'.code -> {
                    if (stack.size >= MAX_DEPTH) return skimDeep()
                    i++
                    val f = ObjFrame()
                    stack.add(f)
                    ws()
                    if (peek() == '}'.code) {
                        i++
                        stack.removeAt(stack.size - 1)
                        return JObject(emptyList())
                    }
                    key(f)
                    return null
                }
                '['.code -> {
                    if (stack.size >= MAX_DEPTH) return skimDeep()
                    i++
                    stack.add(ArrFrame())
                    ws()
                    if (peek() == ']'.code) {
                        i++
                        stack.removeAt(stack.size - 1)
                        return JArray(emptyList())
                    }
                    return null
                }
                else -> return scalar(c)
            }
        }

        private fun scalar(c: Int): JValue = when {
            c == '"'.code -> JString(string())
            c == '-'.code -> if (isLetter(at(i + 1))) word(1) else number()
            c in '0'.code..'9'.code -> number()
            isLetter(c) -> word(0)
            else -> fail("unexpected byte 0x${Integer.toHexString(c)}")
        }

        /**
         * Entered at the opening bracket of the container that would be the 17th. Records the depth fault, then scans to the matching
         * close without building values and applying every check of the normal path, and returns a placeholder that is never used
         * (the depth fault makes the result a reject). It keeps one bit per open container and, per open object, the offset of its
         * first name; the names become a set only when a second one arrives, and the first is then decoded again from its offset.
         */
        private fun skimDeep(): JValue {
            if (tooDeep < 0) tooDeep = i + 1
            val isObject = BitSet()
            var depth = 0
            var firstName = IntArray(64)
            var objects = 0
            var sets: HashMap<Int, HashSet<String>>? = null
            val scratch = StringBuilder()

            fun readName() {
                ws()
                if (peek() != '"'.code) fail("expected a member name")
                val at = i
                scratch.setLength(0)
                scanString(scratch)
                val top = objects - 1
                if (firstName[top] < 0) {
                    firstName[top] = at
                } else {
                    val name = scratch.toString()
                    val all = sets ?: HashMap<Int, HashSet<String>>().also { sets = it }
                    val names = all.getOrPut(top) {
                        val resume = i
                        i = firstName[top]
                        scratch.setLength(0)
                        scanString(scratch)
                        i = resume
                        HashSet<String>().also { it.add(scratch.toString()) }
                    }
                    if (!names.add(name) && duplicate < 0) duplicate = i
                }
                ws()
                if (peek() != ':'.code) fail("expected ':'")
                i++
            }

            while (true) {
                ws()
                var completed = true
                when (val c = peek()) {
                    '{'.code -> {
                        i++
                        ws()
                        if (peek() == '}'.code) {
                            i++
                        } else {
                            isObject.set(depth++)
                            if (objects == firstName.size) firstName = firstName.copyOf(objects * 2)
                            firstName[objects++] = -1
                            readName()
                            completed = false
                        }
                    }
                    '['.code -> {
                        i++
                        ws()
                        if (peek() == ']'.code) {
                            i++
                        } else {
                            isObject.clear(depth++)
                            completed = false
                        }
                    }
                    '"'.code -> {
                        scratch.setLength(0)
                        scanString(scratch)
                    }
                    else -> scalar(c)
                }
                if (!completed) continue
                while (true) {
                    if (depth == 0) return JInt(0)
                    ws()
                    val closer = peek()
                    if (isObject.get(depth - 1)) {
                        if (closer == ','.code) {
                            i++
                            readName()
                            break
                        }
                        if (closer == '}'.code) {
                            i++
                            sets?.remove(objects - 1)
                            objects--
                            depth--
                            continue
                        }
                        fail("expected ',' or '}'")
                    } else {
                        if (closer == ','.code) {
                            i++
                            break
                        }
                        if (closer == ']'.code) {
                            i++
                            depth--
                            continue
                        }
                        fail("expected ',' or ']'")
                    }
                }
            }
        }

        private fun key(f: ObjFrame) {
            if (peek() != '"'.code) fail("expected a member name")
            f.key = string()
            ws()
            if (peek() != ':'.code) fail("expected ':'")
            i++
        }

        private fun ws() {
            while (i < b.size) {
                val c = b[i].toInt()
                if (c == 0x20 || c == 0x09 || c == 0x0A || c == 0x0D) i++ else break
            }
        }

        private fun at(k: Int): Int = if (k < b.size) b[k].toInt() and 0xFF else -1

        private fun peek(): Int = if (i < b.size) b[i].toInt() and 0xFF else fail("unexpected end of input")

        private fun fail(message: String): Nothing = throw SyntaxError(i, message)

        private fun isLetter(c: Int): Boolean = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code

        /** `true`, `false`, `null`, and the four spellings of NaN and Infinity, which are numbers outside the profile. */
        private fun word(skip: Int): JValue {
            val start = i
            i += skip
            val from = i
            while (isLetter(at(i))) i++
            val w = String(b, from, i - from, Charsets.US_ASCII)
            if (skip == 0) {
                when (w) {
                    "true" -> return JBool(true)
                    "false" -> return JBool(false)
                    "null" -> return JNull
                }
            }
            if (w == "NaN" || w == "Infinity") {
                if (nonInteger < 0) nonInteger = start
                return JInt(0)
            }
            i = start
            fail("unknown literal")
        }

        /** The whole lexeme (a maximal run of digits, signs, dots and e/E) is judged as one number. */
        private fun number(): JValue {
            val start = i
            while (i < b.size) {
                val c = b[i].toInt() and 0xFF
                if (c in '0'.code..'9'.code || c == '-'.code || c == '+'.code || c == '.'.code || c == 'e'.code || c == 'E'.code) i++ else break
            }
            val neg = b[start] == '-'.code.toByte()
            val digits = start + if (neg) 1 else 0
            val n = i - digits
            val integerForm = n >= 1 && (0 until n).all { b[digits + it] in '0'.code.toByte()..'9'.code.toByte() } &&
                (n == 1 || b[digits] != '0'.code.toByte())
            if (!integerForm || (neg && n == 1 && b[digits] == '0'.code.toByte())) {
                if (nonInteger < 0) nonInteger = start
                return JInt(0)
            }
            if (n > 16) {
                if (range < 0) range = start
                return JInt(0)
            }
            var magnitude = 0L
            for (k in 0 until n) magnitude = magnitude * 10 + (b[digits + k] - '0'.code.toByte())
            if (magnitude > MAX_SAFE_INT) {
                if (range < 0) range = start
                return JInt(0)
            }
            return JInt(if (neg) -magnitude else magnitude)
        }

        private fun hex4(pos: Int): Int {
            if (pos + 4 > b.size) return -1
            var v = 0
            for (k in 0 until 4) {
                val c = b[pos + k].toInt() and 0xFF
                val d = when (c) {
                    in '0'.code..'9'.code -> c - '0'.code
                    in 'a'.code..'f'.code -> c - 'a'.code + 10
                    in 'A'.code..'F'.code -> c - 'A'.code + 10
                    else -> return -1
                }
                v = v * 16 + d
            }
            return v
        }

        /**
         * Bytes of an invalid UTF-8 sequence become U+FFFD here and are reported by the whole-input check in [parse];
         * a lone surrogate escape becomes U+FFFD and is flagged, so a value that is rejected is never built from it.
         */
        private fun string(): String = StringBuilder().also { scanString(it) }.toString()

        private fun scanString(sb: StringBuilder) {
            i++
            while (true) {
                if (i >= b.size) fail("unterminated string")
                val c = b[i].toInt() and 0xFF
                when {
                    c == '"'.code -> { i++; return }
                    c == '\\'.code -> escape(sb)
                    c < 0x20 -> fail("control character in string")
                    c < 0x80 -> { sb.append(c.toChar()); i++ }
                    else -> {
                        val d = Utf8Strict.decodeAt(b, i)
                        if (d < 0) {
                            sb.append('�')
                            i++
                        } else {
                            sb.appendCodePoint((d shr 3).toInt())
                            i += (d and 7L).toInt()
                        }
                    }
                }
            }
        }

        private fun escape(sb: StringBuilder) {
            val escStart = i
            i++
            if (i >= b.size) fail("unterminated escape")
            when (val e = b[i].toInt().toChar()) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    val u = hex4(i + 1)
                    if (u < 0) fail("bad \\u escape")
                    i += 4
                    if (u in 0xD800..0xDBFF) {
                        val next = if (at(i + 1) == '\\'.code && at(i + 2) == 'u'.code) hex4(i + 3) else -1
                        if (next in 0xDC00..0xDFFF) {
                            sb.append(u.toChar()).append(next.toChar())
                            i += 6
                        } else {
                            if (lone < 0) lone = escStart
                            sb.append('�')
                        }
                    } else if (u in 0xDC00..0xDFFF) {
                        if (lone < 0) lone = escStart
                        sb.append('�')
                    } else {
                        sb.append(u.toChar())
                    }
                }
                else -> fail("bad escape \\$e")
            }
            i++
        }
    }
}
