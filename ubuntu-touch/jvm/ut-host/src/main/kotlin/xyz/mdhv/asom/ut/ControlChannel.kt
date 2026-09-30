package xyz.mdhv.asom.ut

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.JsonRejectCode
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson

/**
 * `asom-ut-ctl/1` (ubuntu-touch.md 7.3): one JSON object per line over the JVM child's stdin and stdout, UTF-8, the lab's
 * strict tokenizer (integers only, C1), at most 1 MiB per line. Private to the app: no other process can reach the pipes,
 * so this is not a contract surface. Every frame carries its type in the member `t`, listed first.
 */
object CtlProtocol {
    const val NAME = "asom-ut-ctl/1"
    const val VERSION = 1L
    const val MAX_LINE_BYTES = 1 shl 20
}

/** Why a line was refused. A fixed vocabulary: it never carries any part of the line. */
enum class BadFrame {
    TOO_LONG, EMPTY_LINE, NOT_JSON, INVALID_UNICODE, NON_INTEGER_NUMBER, NUMBER_RANGE, DUPLICATE_KEY, TRAILING_DATA,
    NOT_OBJECT, MISSING_TYPE, UNKNOWN_TYPE, MISSING_FIELD, BAD_FIELD, UNKNOWN_FIELD,
}

class BadFrameException(val reason: BadFrame) : RuntimeException(reason.name, null, false, false)

class FrameTooLargeException : RuntimeException("frame exceeds ${CtlProtocol.MAX_LINE_BYTES} bytes", null, false, false)

enum class UiLifecycle(val wire: String) {
    ACTIVE("active"), INACTIVE("inactive"), SUSPENDING("suspending");

    companion object {
        fun fromWire(w: String): UiLifecycle? = entries.firstOrNull { it.wire == w }
    }
}

data class ChatMessage(val role: String, val content: String)

enum class PairOp(val wire: String) { BEGIN("begin"), CONFIRM("confirm"), APPROVE("approve") }

enum class ExportKind(val wire: String) { LEDGER("ledger"), MANIFEST("manifest") }

/** UI to node. A closed set: anything else is a protocol violation. */
sealed interface UiFrame {
    data class Hello(val v: Long) : UiFrame
    data class Lifecycle(val state: UiLifecycle) : UiFrame
    data class Borrow(val rid: String, val model: String, val messages: List<ChatMessage>, val maxTokens: Long, val stream: Boolean) : UiFrame
    data class Cancel(val rid: String) : UiFrame
    data class Peers(val open: Boolean) : UiFrame
    data class Pair(val op: PairOp, val value: String?) : UiFrame
    data class Revoke(val peer: String) : UiFrame
    data class LedgerQuery(val since: Long, val limit: Long) : UiFrame
    data class Export(val kind: ExportKind) : UiFrame
    data object SelfTest : UiFrame
    data object Shutdown : UiFrame
}

/** Node to UI. A closed set. */
sealed interface NodeFrame {
    data class HelloAck(val nodeTag: String, val keyStorage: String) : NodeFrame
    data class State(val node: String, val sessions: Long, val lending: String) : NodeFrame
    data class Chunk(val rid: String, val delta: String) : NodeFrame
    data class End(val rid: String, val record: JObject) : NodeFrame
    data class Error(val rid: String?, val code: UiErrorCode) : NodeFrame
    data class PeersList(val list: List<JObject>) : NodeFrame
    data class Sas(val code: String) : NodeFrame
    data class Rows(val rows: List<JObject>) : NodeFrame
    data class ExportReady(val bytes: Long, val sha256: String) : NodeFrame
    data class SelfTestResult(val result: JObject) : NodeFrame
}

object NodeStates {
    const val IDLE = "idle"
    const val ACTIVE = "active"
    const val INTERRUPTED = "interrupted"
    val ALL = setOf(IDLE, ACTIVE, INTERRUPTED)
    const val LENDING_OFF = "off"
}

sealed interface Decoded<out T> {
    class Ok<T>(val frame: T) : Decoded<T>
    class Bad(val reason: BadFrame) : Decoded<Nothing>
}

object FrameCodec {
    private const val MAX_RID = 64
    private const val MAX_MODEL = 200
    private const val MAX_PEER = 64
    private const val MAX_PAIR_VALUE = 8192
    private const val MAX_MESSAGES = 1024
    private const val MAX_ROLE = 32
    private const val MAX_TOKENS = 1_000_000L
    private const val MAX_LEDGER_LIMIT = 500L
    private val RID = Regex("[A-Za-z0-9._:-]{1,$MAX_RID}")

    fun decodeUi(line: ByteArray): Decoded<UiFrame> = decode(line) { obj -> uiFrom(obj) }

    fun decodeNode(line: ByteArray): Decoded<NodeFrame> = decode(line) { obj -> nodeFrom(obj) }

    private fun <T> decode(line: ByteArray, build: (JObject) -> T): Decoded<T> {
        if (line.isEmpty()) return Decoded.Bad(BadFrame.EMPTY_LINE)
        if (line.size > CtlProtocol.MAX_LINE_BYTES) return Decoded.Bad(BadFrame.TOO_LONG)
        val parsed = StrictJson.parse(line)
        val obj = when (parsed) {
            is ParseResult.Reject -> return Decoded.Bad(
                when (parsed.code) {
                    JsonRejectCode.MALFORMED_JSON -> BadFrame.NOT_JSON
                    JsonRejectCode.INVALID_UNICODE -> BadFrame.INVALID_UNICODE
                    JsonRejectCode.NON_INTEGER_NUMBER -> BadFrame.NON_INTEGER_NUMBER
                    JsonRejectCode.NUMBER_RANGE -> BadFrame.NUMBER_RANGE
                    JsonRejectCode.DUPLICATE_KEY -> BadFrame.DUPLICATE_KEY
                    JsonRejectCode.TRAILING_DATA -> BadFrame.TRAILING_DATA
                },
            )
            is ParseResult.Ok -> parsed.value as? JObject ?: return Decoded.Bad(BadFrame.NOT_OBJECT)
        }
        return try {
            Decoded.Ok(build(obj))
        } catch (e: BadFrameException) {
            Decoded.Bad(e.reason)
        }
    }

    // ---------------------------------------------------------------- UI -> node

    private fun uiFrom(o: JObject): UiFrame {
        val type = (o["t"] ?: throw BadFrameException(BadFrame.MISSING_TYPE)) as? JString ?: throw BadFrameException(BadFrame.BAD_FIELD)
        return when (type.value) {
            "hello" -> Fields(o, setOf("t", "v")).run { UiFrame.Hello(int("v", 0, 1_000_000)) }
            "lifecycle" -> Fields(o, setOf("t", "state")).run {
                UiFrame.Lifecycle(UiLifecycle.fromWire(str("state", 1, 16)) ?: throw BadFrameException(BadFrame.BAD_FIELD))
            }
            "borrow" -> Fields(o, setOf("t", "rid", "model", "messages", "maxTokens", "stream")).run {
                UiFrame.Borrow(rid("rid"), model("model"), messages("messages"), int("maxTokens", 1, MAX_TOKENS), bool("stream"))
            }
            "cancel" -> Fields(o, setOf("t", "rid")).run { UiFrame.Cancel(rid("rid")) }
            "peers" -> Fields(o, setOf("t", "op")).run {
                UiFrame.Peers(
                    when (str("op", 1, 8)) {
                        "open" -> true
                        "close" -> false
                        else -> throw BadFrameException(BadFrame.BAD_FIELD)
                    },
                )
            }
            "pair" -> {
                val f = Fields(o, setOf("t", "op", "value"))
                val op = PairOp.entries.firstOrNull { it.wire == f.str("op", 1, 16) } ?: throw BadFrameException(BadFrame.BAD_FIELD)
                if (op == PairOp.APPROVE) {
                    if (o["value"] != null) throw BadFrameException(BadFrame.UNKNOWN_FIELD)
                    UiFrame.Pair(op, null)
                } else {
                    UiFrame.Pair(op, f.str("value", 1, MAX_PAIR_VALUE, allowControl = true))
                }
            }
            "revoke" -> Fields(o, setOf("t", "peer")).run { UiFrame.Revoke(str("peer", 1, MAX_PEER)) }
            "ledger" -> Fields(o, setOf("t", "since", "limit")).run { UiFrame.LedgerQuery(int("since", 0, MAX_SAFE), int("limit", 1, MAX_LEDGER_LIMIT)) }
            "export" -> Fields(o, setOf("t", "kind")).run {
                UiFrame.Export(ExportKind.entries.firstOrNull { it.wire == str("kind", 1, 16) } ?: throw BadFrameException(BadFrame.BAD_FIELD))
            }
            "selftest" -> Fields(o, setOf("t")).run { UiFrame.SelfTest }
            "shutdown" -> Fields(o, setOf("t")).run { UiFrame.Shutdown }
            else -> throw BadFrameException(BadFrame.UNKNOWN_TYPE)
        }
    }

    private const val MAX_SAFE = 9_007_199_254_740_991L

    private class Fields(val o: JObject, allowed: Set<String>) {
        init {
            for ((k, _) in o.members) if (k !in allowed) throw BadFrameException(BadFrame.UNKNOWN_FIELD)
        }

        fun value(k: String): JValue = o[k] ?: throw BadFrameException(BadFrame.MISSING_FIELD)

        fun str(k: String, min: Int, max: Int, allowControl: Boolean = false): String {
            val s = (value(k) as? JString ?: throw BadFrameException(BadFrame.BAD_FIELD)).value
            if (s.length < min || s.length > max) throw BadFrameException(BadFrame.BAD_FIELD)
            if (!allowControl && s.any { it.isISOControl() }) throw BadFrameException(BadFrame.BAD_FIELD)
            return s
        }

        fun int(k: String, min: Long, max: Long): Long {
            val n = (value(k) as? JInt ?: throw BadFrameException(BadFrame.BAD_FIELD)).value
            if (n < min || n > max) throw BadFrameException(BadFrame.BAD_FIELD)
            return n
        }

        fun bool(k: String): Boolean = (value(k) as? JBool ?: throw BadFrameException(BadFrame.BAD_FIELD)).value

        fun rid(k: String): String {
            val s = (value(k) as? JString ?: throw BadFrameException(BadFrame.BAD_FIELD)).value
            if (!RID.matches(s)) throw BadFrameException(BadFrame.BAD_FIELD)
            return s
        }

        fun model(k: String): String = str(k, 1, MAX_MODEL)

        fun messages(k: String): List<ChatMessage> {
            val a = value(k) as? JArray ?: throw BadFrameException(BadFrame.BAD_FIELD)
            if (a.items.isEmpty() || a.items.size > MAX_MESSAGES) throw BadFrameException(BadFrame.BAD_FIELD)
            return a.items.map { item ->
                val m = item as? JObject ?: throw BadFrameException(BadFrame.BAD_FIELD)
                val f = Fields(m, setOf("role", "content"))
                val content = (f.value("content") as? JString ?: throw BadFrameException(BadFrame.BAD_FIELD)).value
                ChatMessage(f.str("role", 1, MAX_ROLE), content)
            }
        }
    }

    fun encodeUi(f: UiFrame): ByteArray = encodeValue(uiJson(f)).toByteArray(Charsets.UTF_8)

    private fun uiJson(f: UiFrame): JObject = when (f) {
        is UiFrame.Hello -> obj("hello", "v" to JInt(f.v))
        is UiFrame.Lifecycle -> obj("lifecycle", "state" to JString(f.state.wire))
        is UiFrame.Borrow -> obj(
            "borrow", "rid" to JString(f.rid), "model" to JString(f.model),
            "messages" to JArray(f.messages.map { JObject(listOf("role" to JString(it.role), "content" to JString(it.content))) }),
            "maxTokens" to JInt(f.maxTokens), "stream" to JBool(f.stream),
        )
        is UiFrame.Cancel -> obj("cancel", "rid" to JString(f.rid))
        is UiFrame.Peers -> obj("peers", "op" to JString(if (f.open) "open" else "close"))
        is UiFrame.Pair -> if (f.value == null) obj("pair", "op" to JString(f.op.wire)) else obj("pair", "op" to JString(f.op.wire), "value" to JString(f.value))
        is UiFrame.Revoke -> obj("revoke", "peer" to JString(f.peer))
        is UiFrame.LedgerQuery -> obj("ledger", "since" to JInt(f.since), "limit" to JInt(f.limit))
        is UiFrame.Export -> obj("export", "kind" to JString(f.kind.wire))
        UiFrame.SelfTest -> obj("selftest")
        UiFrame.Shutdown -> obj("shutdown")
    }

    // ---------------------------------------------------------------- node -> UI

    fun encode(f: NodeFrame): ByteArray = encodeValue(nodeJson(f)).toByteArray(Charsets.UTF_8)

    private fun nodeJson(f: NodeFrame): JObject = when (f) {
        is NodeFrame.HelloAck -> obj("hello_ack", "nodeTag" to JString(f.nodeTag), "keyStorage" to JString(f.keyStorage))
        is NodeFrame.State -> obj("state", "node" to JString(f.node), "sessions" to JInt(f.sessions), "lending" to JString(f.lending))
        is NodeFrame.Chunk -> obj("chunk", "rid" to JString(f.rid), "delta" to JString(f.delta))
        is NodeFrame.End -> obj("end", "rid" to JString(f.rid), "record" to f.record)
        is NodeFrame.Error -> obj("error", "rid" to (f.rid?.let { JString(it) } ?: JNull), "code" to JString(f.code.name))
        is NodeFrame.PeersList -> obj("peers", "list" to JArray(f.list))
        is NodeFrame.Sas -> obj("sas", "code" to JString(f.code))
        is NodeFrame.Rows -> obj("rows", "rows" to JArray(f.rows))
        is NodeFrame.ExportReady -> obj("export_ready", "bytes" to JInt(f.bytes), "sha256" to JString(f.sha256))
        is NodeFrame.SelfTestResult -> obj("selftest", "result" to f.result)
    }

    private fun nodeFrom(o: JObject): NodeFrame {
        val type = (o["t"] ?: throw BadFrameException(BadFrame.MISSING_TYPE)) as? JString ?: throw BadFrameException(BadFrame.BAD_FIELD)
        fun objList(f: Fields, k: String): List<JObject> =
            (f.value(k) as? JArray ?: throw BadFrameException(BadFrame.BAD_FIELD)).items.map { it as? JObject ?: throw BadFrameException(BadFrame.BAD_FIELD) }
        return when (type.value) {
            "hello_ack" -> Fields(o, setOf("t", "nodeTag", "keyStorage")).run { NodeFrame.HelloAck(str("nodeTag", 1, 64), str("keyStorage", 1, 32)) }
            "state" -> Fields(o, setOf("t", "node", "sessions", "lending")).run {
                val node = str("node", 1, 16)
                if (node !in NodeStates.ALL) throw BadFrameException(BadFrame.BAD_FIELD)
                NodeFrame.State(node, int("sessions", 0, 1_000_000), str("lending", 1, 16))
            }
            "chunk" -> Fields(o, setOf("t", "rid", "delta")).run {
                NodeFrame.Chunk(rid("rid"), (value("delta") as? JString ?: throw BadFrameException(BadFrame.BAD_FIELD)).value)
            }
            "end" -> Fields(o, setOf("t", "rid", "record")).run { NodeFrame.End(rid("rid"), value("record") as? JObject ?: throw BadFrameException(BadFrame.BAD_FIELD)) }
            "error" -> Fields(o, setOf("t", "rid", "code")).run {
                val ridOrNull = when (value("rid")) {
                    JNull -> null
                    is JString -> rid("rid")
                    else -> throw BadFrameException(BadFrame.BAD_FIELD)
                }
                NodeFrame.Error(ridOrNull, UiErrorCode.fromWire(str("code", 1, 64)) ?: throw BadFrameException(BadFrame.BAD_FIELD))
            }
            "peers" -> Fields(o, setOf("t", "list")).run { NodeFrame.PeersList(objList(this, "list")) }
            "sas" -> Fields(o, setOf("t", "code")).run { NodeFrame.Sas(str("code", 1, 32)) }
            "rows" -> Fields(o, setOf("t", "rows")).run { NodeFrame.Rows(objList(this, "rows")) }
            "export_ready" -> Fields(o, setOf("t", "bytes", "sha256")).run { NodeFrame.ExportReady(int("bytes", 0, MAX_SAFE), str("sha256", 64, 64)) }
            "selftest" -> Fields(o, setOf("t", "result")).run { NodeFrame.SelfTestResult(value("result") as? JObject ?: throw BadFrameException(BadFrame.BAD_FIELD)) }
            else -> throw BadFrameException(BadFrame.UNKNOWN_TYPE)
        }
    }

    private fun obj(type: String, vararg members: Pair<String, JValue>): JObject = JObject(listOf("t" to JString(type)) + members.toList())

    /** Insertion-ordered, compact, single line: every control character, U+2028 and U+2029 are escaped, so no raw line break can occur. */
    fun encodeValue(v: JValue): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: JValue) {
        when (v) {
            JNull -> sb.append("null")
            is JBool -> sb.append(if (v.value) "true" else "false")
            is JInt -> sb.append(v.value)
            is JString -> string(sb, v.value)
            is JArray -> {
                sb.append('[')
                v.items.forEachIndexed { i, item -> if (i > 0) sb.append(','); write(sb, item) }
                sb.append(']')
            }
            is JObject -> {
                sb.append('{')
                v.members.forEachIndexed { i, (k, item) -> if (i > 0) sb.append(','); string(sb, k); sb.append(':'); write(sb, item) }
                sb.append('}')
            }
        }
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' || c == ' ' || c == ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}

sealed interface Line {
    class Data(val bytes: ByteArray) : Line
    data object TooLong : Line
    data object Eof : Line
}

/**
 * Splits a byte stream into lines. The cap is enforced while reading, before the buffer can grow past it, so a hostile
 * peer cannot make the node allocate more than [cap] bytes for one line. After [Line.TooLong] the reader is finished.
 */
class LineReader(private val input: InputStream, private val cap: Int = CtlProtocol.MAX_LINE_BYTES) {
    private val buf = ByteArray(8192)
    private var pos = 0
    private var lim = 0
    private val acc = ByteArrayOutputStream()

    fun next(): Line {
        acc.reset()
        while (true) {
            if (pos == lim) {
                val n = input.read(buf, 0, buf.size)
                if (n < 0) return Line.Eof
                pos = 0
                lim = n
            }
            var i = pos
            while (i < lim && buf[i] != NL) i++
            val chunk = i - pos
            if (acc.size().toLong() + chunk > cap) return Line.TooLong
            acc.write(buf, pos, chunk)
            if (i < lim) {
                pos = i + 1
                return Line.Data(acc.toByteArray())
            }
            pos = lim
        }
    }

    private companion object {
        const val NL: Byte = '\n'.code.toByte()
    }
}

/** Writes one frame per line. Thread-safe. A frame over the cap is refused, never truncated. */
class FrameWriter(private val out: OutputStream) {
    private val lock = Any()

    fun write(frame: NodeFrame) {
        val bytes = FrameCodec.encode(frame)
        if (bytes.size > CtlProtocol.MAX_LINE_BYTES) throw FrameTooLargeException()
        synchronized(lock) {
            out.write(bytes)
            out.write('\n'.code)
            out.flush()
        }
    }
}
