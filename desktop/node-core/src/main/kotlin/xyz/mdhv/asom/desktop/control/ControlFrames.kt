package xyz.mdhv.asom.desktop.control

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import xyz.mdhv.asom.desktop.json.StrictJson
import xyz.mdhv.asom.desktop.json.StrictJsonException

/**
 * The owner-CLI command set (CD-24, linux.md 7.3): CLOSED. Names the spec does not give (the `pair-*` family beyond
 * `pair-confirm`) are not invented here; the pairing track adds them under the D23 ruling.
 */
enum class ControlCommand(val wire: String, val requiresTtyConfirmation: Boolean = false) {
    STATUS("status"),
    WATCH("watch"),
    CHAT("chat"),
    LEND("lend"),
    LAN_CONFIRM("lan-confirm", requiresTtyConfirmation = true),
    PAIR_CONFIRM("pair-confirm", requiresTtyConfirmation = true),
    PEERS("peers"),
    LEDGER_EXPORT("ledger-export"),
    BENCH("bench"),
    UNLOCK("unlock"),
    SHUTDOWN("shutdown"),
    RESTORE("restore", requiresTtyConfirmation = true);

    companion object {
        fun fromWire(w: String): ControlCommand? = entries.firstOrNull { it.wire == w }
    }
}

enum class ControlCode { NOT_IMPLEMENTED, BAD_REQUEST, UNKNOWN_COMMAND, FORBIDDEN, FRAME_TOO_LARGE, INTERNAL }

data class ControlRequest(val id: Long, val command: ControlCommand, val args: JsonObject = JsonObject(emptyMap()))

data class ControlResponse(
    val id: Long,
    val ok: Boolean,
    val code: ControlCode? = null,
    val message: String? = null,
    val result: JsonObject? = null,
) {
    companion object {
        fun notImplemented(id: Long, feature: String, track: String) =
            ControlResponse(id, ok = false, code = ControlCode.NOT_IMPLEMENTED, message = "$feature ($track)")
    }
}

/** What the OS reports about the local caller: `local-uid:<user name>` (Linux, macOS), `local-sid:<sid>(acl)` (Windows). */
data class CallerIdentity(val callerPkg: String)

fun interface ControlHandler {
    fun handle(request: ControlRequest, caller: CallerIdentity): ControlResponse
}

class ControlFrameException(val code: ControlCode, message: String) : IllegalArgumentException(message)

/** 4-byte big-endian length + UTF-8 JSON, at most 1 MiB, integers only (C1). */
object ControlFrames {
    const val MAX_FRAME_BYTES = 1 shl 20

    fun frame(payloadUtf8: ByteArray): ByteArray {
        if (payloadUtf8.size > MAX_FRAME_BYTES) throw ControlFrameException(ControlCode.FRAME_TOO_LARGE, "frame exceeds 1 MiB")
        val n = payloadUtf8.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + payloadUtf8
    }

    fun write(out: OutputStream, payload: String) {
        out.write(frame(payload.toByteArray(Charsets.UTF_8)))
        out.flush()
    }

    /** Reads one frame. The length is checked BEFORE any allocation, so a hostile length cannot exhaust memory. */
    fun read(input: InputStream): String {
        val h = ByteArray(4)
        readFully(input, h)
        val n = ((h[0].toInt() and 0xff) shl 24) or ((h[1].toInt() and 0xff) shl 16) or ((h[2].toInt() and 0xff) shl 8) or (h[3].toInt() and 0xff)
        if (n < 0 || n > MAX_FRAME_BYTES) throw ControlFrameException(ControlCode.FRAME_TOO_LARGE, "declared length exceeds 1 MiB")
        val body = ByteArray(n)
        readFully(input, body)
        return String(body, Charsets.UTF_8)
    }

    private fun readFully(input: InputStream, into: ByteArray) {
        var off = 0
        while (off < into.size) {
            val r = input.read(into, off, into.size - off)
            if (r < 0) throw EOFException("truncated frame")
            off += r
        }
    }

    fun encodeRequest(r: ControlRequest): String {
        val m = LinkedHashMap<String, JsonElement>()
        m["id"] = JsonPrimitive(r.id)
        m["cmd"] = JsonPrimitive(r.command.wire)
        if (r.args.isNotEmpty()) m["args"] = r.args
        return JsonObject(m).toString()
    }

    fun decodeRequest(text: String): ControlRequest {
        val root = try {
            StrictJson.parse(text)
        } catch (e: StrictJsonException) {
            throw ControlFrameException(ControlCode.BAD_REQUEST, e.message ?: "bad JSON")
        }
        val obj = root as? JsonObject ?: throw ControlFrameException(ControlCode.BAD_REQUEST, "request must be an object")
        val extra = obj.keys - setOf("id", "cmd", "args")
        if (extra.isNotEmpty()) throw ControlFrameException(ControlCode.BAD_REQUEST, "unknown request field ${extra.first()}")
        val id = (obj["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            ?: throw ControlFrameException(ControlCode.BAD_REQUEST, "id must be an integer")
        val cmdName = (obj["cmd"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: throw ControlFrameException(ControlCode.BAD_REQUEST, "cmd must be a string")
        val cmd = ControlCommand.fromWire(cmdName) ?: throw ControlFrameException(ControlCode.UNKNOWN_COMMAND, "unknown command")
        val args = when (val a = obj["args"]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> a
            else -> throw ControlFrameException(ControlCode.BAD_REQUEST, "args must be an object")
        }
        return ControlRequest(id, cmd, args)
    }

    fun encodeResponse(r: ControlResponse): String {
        val m = LinkedHashMap<String, JsonElement>()
        m["id"] = JsonPrimitive(r.id)
        m["ok"] = JsonPrimitive(r.ok)
        m["code"] = r.code?.let { JsonPrimitive(it.name) } ?: JsonNull
        if (r.message != null) m["message"] = JsonPrimitive(r.message)
        if (r.result != null) m["result"] = r.result
        return JsonObject(m).toString()
    }
}
