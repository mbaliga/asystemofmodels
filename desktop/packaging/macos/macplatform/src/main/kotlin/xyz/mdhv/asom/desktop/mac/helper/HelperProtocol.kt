package xyz.mdhv.asom.desktop.mac.helper

import java.util.Base64

/** A message field value. Base64 fields carry the DECODED bytes; the codec encodes and decodes them strictly. */
sealed interface HValue {
    data class I(val v: Long) : HValue
    data class B(val v: Boolean) : HValue
    data class T(val v: String) : HValue
    class Bytes(val v: ByteArray) : HValue {
        override fun equals(other: Any?): Boolean = other is Bytes && v.contentEquals(other.v)
        override fun hashCode(): Int = v.contentHashCode()
        override fun toString(): String = "Bytes(${v.size})"
    }

    data object Null : HValue
}

class Fields(val items: List<Pair<String, HValue>> = emptyList()) {
    operator fun get(name: String): HValue? = items.firstOrNull { it.first == name }?.second
    fun int(name: String): Long? = (this[name] as? HValue.I)?.v
    fun bool(name: String): Boolean? = (this[name] as? HValue.B)?.v
    fun text(name: String): String? = (this[name] as? HValue.T)?.v
    fun bytes(name: String): ByteArray? = (this[name] as? HValue.Bytes)?.v
    fun isNull(name: String): Boolean = this[name] is HValue.Null

    companion object {
        fun of(vararg pairs: Pair<String, HValue>) = Fields(pairs.toList())
    }
}

class Request(val op: String, val id: Long? = null, val fields: Fields = Fields())

sealed interface Reply {
    class Success(val op: String, val id: Long?, val fields: Fields) : Reply
    class Failure(val id: Long?, val code: String, val message: String) : Reply
}

class Event(val name: String, val fields: Fields = Fields())

/** The JSON-Lines codec of helper-protocol/SCHEMA.md: decode order of section 3, canonical encoding of section 2. */
object HelperCodec {
    // ---- strict base64 (SCHEMA section 2) ------------------------------------------------------------------------------

    fun base64Encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Null unless [text] is canonical standard base64 with padding: re-encoding the decoded bytes must give the same text. */
    fun base64Decode(text: String): ByteArray? {
        val raw = try {
            Base64.getDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return if (base64Encode(raw) == text) raw else null
    }

    // ---- decoding -------------------------------------------------------------------------------------------------------

    fun decodeRequest(line: ByteArray): Request {
        val obj = parseObject(line)
        val id = usableId(obj)
        val opv = obj["op"] ?: throw Reject(RejectCode.MISSING_FIELD, id)
        val op = (opv as? JV.Str)?.value ?: throw Reject(RejectCode.BAD_FIELD, id)
        val params = ProtocolSpec.requestParams(op) ?: throw Reject(RejectCode.UNKNOWN_OP, id)
        val fields = decodeMembers(obj, "op", allowId = true, spec = params, bestId = id)
        return Request(op, id, fields)
    }

    /** Decodes a response to the request named [op]. */
    fun decodeReply(op: String, line: ByteArray): Reply {
        val obj = parseObject(line)
        val id = usableId(obj)
        val okv = obj["ok"] ?: throw Reject(RejectCode.MISSING_FIELD, id)
        val ok = (okv as? JV.Bool)?.value ?: throw Reject(RejectCode.BAD_FIELD, id)
        if (ok) {
            val spec = ProtocolSpec.replyFields(op) ?: throw Reject(RejectCode.UNKNOWN_OP, id)
            return Reply.Success(op, id, decodeMembers(obj, "ok", allowId = true, spec = spec, bestId = id))
        }
        val f = decodeMembers(obj, "ok", allowId = true, spec = ProtocolSpec.errorFields, bestId = id)
        return Reply.Failure(id, f.text("code") ?: "", f.text("message") ?: "")
    }

    fun decodeEvent(line: ByteArray): Event {
        val obj = parseObject(line)
        val evv = obj["ev"] ?: throw Reject(RejectCode.MISSING_FIELD)
        val name = (evv as? JV.Str)?.value ?: throw Reject(RejectCode.BAD_FIELD)
        val spec = ProtocolSpec.eventFields(name) ?: throw Reject(RejectCode.UNKNOWN_OP)
        return Event(name, decodeMembers(obj, "ev", allowId = false, spec = spec, bestId = null))
    }

    /** True when the line, once parsed, is an object with an `ev` member: the reader thread routes it to the event path. */
    fun isEventLine(line: ByteArray): Boolean = try {
        (parseObject(line)["ev"]) != null
    } catch (_: Reject) {
        false
    }

    /** The `id` of a response line, when the line parses far enough to have a usable one. */
    fun replyId(line: ByteArray): Long? = try {
        usableId(parseObject(line))
    } catch (_: Reject) {
        null
    }

    internal fun parseObject(line: ByteArray): JV.Obj {
        if (line.size > ProtocolSpec.MAX_LINE_BYTES) throw Reject(RejectCode.LINE_TOO_LONG)
        for (b in line) if (b == 0x0A.toByte() || b == 0x0D.toByte()) throw Reject(RejectCode.MALFORMED_JSON)
        val value = try {
            HelperJson.parse(HelperJson.decodeUtf8(line))
        } catch (_: JsonProfileException) {
            throw Reject(RejectCode.MALFORMED_JSON)
        }
        return value as? JV.Obj ?: throw Reject(RejectCode.NOT_OBJECT)
    }

    private fun usableId(obj: JV.Obj): Long? {
        val v = (obj["id"] as? JV.Num)?.value ?: return null
        return if (v in 0..ProtocolSpec.MAX_SAFE_INTEGER) v else null
    }

    private fun decodeMembers(obj: JV.Obj, discriminator: String, allowId: Boolean, spec: List<FieldSpec>, bestId: Long?): Fields {
        for ((k, _) in obj.members) {
            val known = k == discriminator || (allowId && k == "id") || spec.any { it.name == k }
            if (!known) throw Reject(RejectCode.UNKNOWN_FIELD, bestId)
        }
        for (s in spec) if (obj[s.name] == null) throw Reject(RejectCode.MISSING_FIELD, bestId)
        if (allowId) {
            obj["id"]?.let { idv ->
                val n = (idv as? JV.Num)?.value
                if (n == null || n < 0 || n > ProtocolSpec.MAX_SAFE_INTEGER) throw Reject(RejectCode.BAD_FIELD, bestId)
            }
        }
        val items = ArrayList<Pair<String, HValue>>(spec.size)
        for (s in spec) {
            val hv = convert(obj[s.name]!!, s)
            if (hv == null || !validate(hv, s)) throw Reject(RejectCode.BAD_FIELD, bestId)
            items += s.name to hv
        }
        return Fields(items)
    }

    private fun convert(v: JV, s: FieldSpec): HValue? = when (v) {
        JV.Null -> HValue.Null
        is JV.Num -> HValue.I(v.value)
        is JV.Bool -> HValue.B(v.value)
        is JV.Str -> if (s.type is FieldType.Bytes) base64Decode(v.value)?.let { HValue.Bytes(it) } else HValue.T(v.value)
        is JV.Obj, is JV.Arr -> null
    }

    // ---- validation (shared by decode and encode) -------------------------------------------------------------------------

    internal fun validate(v: HValue, s: FieldSpec): Boolean = when (v) {
        HValue.Null -> s.nullable
        is HValue.I -> (s.type as? FieldType.IntRange)?.let { v.v >= it.min && v.v <= it.max } ?: false
        is HValue.B -> s.type is FieldType.Bool
        is HValue.Bytes -> (s.type as? FieldType.Bytes)?.let { v.v.size >= it.min && v.v.size <= it.max } ?: false
        is HValue.T -> when (val t = s.type) {
            is FieldType.OneOf -> v.v in t.options
            is FieldType.Text -> {
                val n = v.v.toByteArray(Charsets.UTF_8).size
                n >= t.min && n <= t.max && textRule(t, v.v)
            }
            else -> false
        }
    }

    private fun noControl(s: String) = s.none { it < ' ' || it == '\u007F' }

    private fun digitsAt(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i] in '0'..'9') i++
        return i
    }

    private fun textRule(t: FieldType.Text, s: String): Boolean = when (t.rule) {
        TextRule.NO_CONTROL -> noControl(s)
        TextRule.ABSOLUTE_PATH -> s.startsWith("/") && noControl(s)
        TextRule.PRINTABLE_ASCII -> s.none { it < ' ' || it > '~' }
        TextRule.EXACT -> s == t.exact
        TextRule.SEMVER -> semver(s)
        TextRule.MACOS_VERSION -> macosVersion(s)
    }

    private fun semver(s: String): Boolean {
        var i = 0
        for (k in 0 until 3) {
            val j = digitsAt(s, i)
            if (j == i) return false
            i = j
            if (k < 2) {
                if (i >= s.length || s[i] != '.') return false
                i++
            }
        }
        if (i == s.length) return true
        if (s[i] != '-') return false
        i++
        if (i == s.length) return false
        while (i < s.length) {
            val c = s[i]
            if (!(c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z' || c == '.' || c == '-')) return false
            i++
        }
        return true
    }

    private fun macosVersion(s: String): Boolean {
        var i = 0
        var parts = 0
        while (true) {
            val j = digitsAt(s, i)
            if (j == i) return false
            i = j
            parts++
            if (i == s.length) break
            if (s[i] != '.') return false
            i++
        }
        return parts in 2..3
    }

    // ---- encoding --------------------------------------------------------------------------------------------------------

    fun encode(r: Request): ByteArray {
        val spec = ProtocolSpec.requestParams(r.op) ?: throw Reject(RejectCode.UNKNOWN_OP)
        val sb = StringBuilder("{\"op\":")
        HelperJson.appendString(sb, r.op)
        appendIdAndFields(sb, r.id, r.fields, spec)
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun encode(reply: Reply): ByteArray {
        val sb: StringBuilder
        when (reply) {
            is Reply.Success -> {
                val spec = ProtocolSpec.replyFields(reply.op) ?: throw Reject(RejectCode.UNKNOWN_OP)
                sb = StringBuilder("{\"ok\":true")
                appendIdAndFields(sb, reply.id, reply.fields, spec)
            }
            is Reply.Failure -> {
                sb = StringBuilder("{\"ok\":false")
                appendIdAndFields(sb, reply.id, Fields.of("code" to HValue.T(reply.code), "message" to HValue.T(reply.message)), ProtocolSpec.errorFields)
            }
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun encode(e: Event): ByteArray {
        val spec = ProtocolSpec.eventFields(e.name) ?: throw Reject(RejectCode.UNKNOWN_OP)
        val sb = StringBuilder("{\"ev\":")
        HelperJson.appendString(sb, e.name)
        appendIdAndFields(sb, null, e.fields, spec)
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun appendIdAndFields(sb: StringBuilder, id: Long?, fields: Fields, spec: List<FieldSpec>) {
        if (id != null) {
            if (id < 0 || id > ProtocolSpec.MAX_SAFE_INTEGER) throw Reject(RejectCode.BAD_FIELD)
            sb.append(",\"id\":").append(id)
        }
        for ((name, _) in fields.items) if (spec.none { it.name == name }) throw Reject(RejectCode.UNKNOWN_FIELD)
        for (s in spec) {
            val v = fields[s.name] ?: throw Reject(RejectCode.MISSING_FIELD)
            if (!validate(v, s)) throw Reject(RejectCode.BAD_FIELD)
            sb.append(',')
            HelperJson.appendString(sb, s.name)
            sb.append(':')
            when (v) {
                is HValue.I -> sb.append(v.v)
                is HValue.B -> sb.append(if (v.v) "true" else "false")
                HValue.Null -> sb.append("null")
                is HValue.T -> HelperJson.appendString(sb, v.v)
                is HValue.Bytes -> HelperJson.appendString(sb, base64Encode(v.v))
            }
        }
        sb.append('}')
    }
}
