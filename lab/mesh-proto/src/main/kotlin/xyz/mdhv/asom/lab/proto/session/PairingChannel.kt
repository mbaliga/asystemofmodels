package xyz.mdhv.asom.lab.proto.session

import java.io.IOException
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.ledger.NodeLedger
import xyz.mdhv.asom.lab.proto.pairing.MsgParse
import xyz.mdhv.asom.lab.proto.pairing.PairMessages
import xyz.mdhv.asom.lab.proto.transport.MeshConnection
import xyz.mdhv.asom.lab.proto.wire.ConnMode
import xyz.mdhv.asom.lab.proto.wire.FrameTypes
import xyz.mdhv.asom.lab.proto.wire.Inbound
import xyz.mdhv.asom.lab.proto.wire.MessageCodec
import xyz.mdhv.asom.lab.proto.wire.PairMsg
import xyz.mdhv.asom.lab.proto.wire.Parsed
import xyz.mdhv.asom.lab.proto.wire.PeerError
import xyz.mdhv.asom.lab.proto.wire.PeerRole
import xyz.mdhv.asom.lab.proto.wire.RawFrame

/**
 * The per-frame ledger side of a pairing-mode connection (LAB_SPEC 7.6: one `PAIRING` row per `PAIR_*` frame on each node, `SESSION` open and close). The
 * bodies of the messages belong to the pairing track: this class carries them as opaque payloads to [onFrame] and writes the rows, with the same
 * durable-before rule and the same FC-2 as an established session. The session id is the unpadded base64url of the first 16 bytes of `PAIR_HELLO.nonceS`,
 * which both sides know (LAB_SPEC 7.5): the listener derives it when it sends `PAIR_HELLO`, the dialer learns it when `PAIR_HELLO` arrives and groups its
 * earlier rows under a local connection id (ERRATA ERR-LL-2).
 */
class PairingChannel(private val ledger: NodeLedger, conn: MeshConnection, ids: IdSource) {
    private val wire = Wire(conn)
    private val role = conn.role
    private val rows = SessionRows(ledger, ids.b64(16), null, "unknown", null, wire, 0) { failClosedNow() }
    private var closed = false
    private val lock = Any()

    /** Receives each `PAIR_*` payload after its row is durable. */
    var onFrame: (type: Int, payload: ByteArray) -> Unit = { _, _ -> }

    val sessionId: String get() = rows.sessionId

    val isClosed: Boolean get() = closed

    init {
        require(conn.mode == ConnMode.PAIRING) { "a pairing channel runs on a pairing-mode connection" }
    }

    private fun <T> guarded(default: T, block: () -> T): T = synchronized(lock) {
        try {
            block()
        } catch (e: FailClosed) {
            default
        } catch (e: IOException) {
            closeNow()
            default
        }
    }

    private fun idFrom(hello: ByteArray): String? {
        val ok = PairMessages.parseHello(hello) as? MsgParse.Ok ?: return null
        return Base64Strict.encodeUrlNoPad(ok.value.nonceS.copyOfRange(0, 16))
    }

    private fun openIfListener() {
        if (role == PeerRole.TLS_SERVER && !rows.opened) rows.open("pairing")
    }

    /** Sends one `PAIR_*` frame on stream 1: row first, then the frame. */
    fun send(type: Int, payload: ByteArray) = guarded(Unit) {
        check(!closed && FrameTypes.isPair(type)) { "a PAIR_* frame on an open pairing channel" }
        val frame = RawFrame(type, 1, payload)
        if (type == FrameTypes.PAIR_HELLO) {
            idFrom(payload)?.let {
                rows.sessionId = it
                rows.joinId = it
            }
        }
        openIfListener()
        rows.control(rows.frameRow(xyz.mdhv.asom.lab.ledger.MeshKind.PAIRING, FrameTypes.nameOf(type), out = appBytes(frame)))
        wire.write(frame)
    }

    fun onBytes(buf: ByteArray, off: Int = 0, len: Int = buf.size - off) = guarded(Unit) {
        if (closed) return@guarded
        for (ev in wire.decoder.feed(buf, off, len)) {
            if (closed) break
            when (ev) {
                is Inbound.Frame -> inbound(ev.frame)
                is Inbound.ExtIgnored -> {
                    openIfListener()
                    rows.control(rows.frameRow(xyz.mdhv.asom.lab.ledger.MeshKind.CONTROL, "EXT_IGNORED", inn = ev.appBytes))
                }
                is Inbound.Failure -> {
                    openIfListener()
                    val err = PeerError(ev.error)
                    rows.control(rows.frameRow(xyz.mdhv.asom.lab.ledger.MeshKind.CONTROL, "ERROR:${ev.error.name}", out = appBytes(MessageCodec.frame(err, 0))))
                    wire.write(MessageCodec.frame(err, 0))
                    closeNow()
                }
            }
        }
    }

    fun pumpAvailable(maxChunk: Int = 4096): Int {
        val buf: ByteArray
        val n: Int
        synchronized(lock) {
            if (closed) return 0
            val avail = wire.input.available()
            if (avail <= 0) return 0
            buf = ByteArray(minOf(avail, maxChunk))
            n = wire.input.read(buf, 0, buf.size)
        }
        if (n > 0) onBytes(buf, 0, n)
        return maxOf(n, 0)
    }

    private fun inbound(f: RawFrame) {
        openIfListener()
        when (val p = MessageCodec.parse(f)) {
            is Parsed.Reject -> {
                val err = PeerError(p.error)
                val frame = MessageCodec.frame(err, 0)
                rows.control(rows.frameRow(xyz.mdhv.asom.lab.ledger.MeshKind.CONTROL, "ERROR:${p.error.name}", out = appBytes(frame)))
                wire.write(frame)
                closeNow()
            }
            is Parsed.Ok -> when (val m = p.message) {
                is PairMsg -> {
                    if (f.type == FrameTypes.PAIR_HELLO && rows.joinId == null) idFrom(f.payload)?.let { rows.joinId = it }
                    rows.control(rows.frameRow(xyz.mdhv.asom.lab.ledger.MeshKind.PAIRING, FrameTypes.nameOf(f.type), inn = appBytes(f)))
                    onFrame(f.type, f.payload)
                }
                is PeerError -> {
                    rows.control(rows.frameRow(xyz.mdhv.asom.lab.ledger.MeshKind.CONTROL, "ERROR:${m.code.name}", inn = appBytes(f)))
                    closeNow()
                }
                else -> Unit
            }
        }
    }

    fun close() = guarded(Unit) { closeNow() }

    private fun closeNow() {
        if (closed) return
        closed = true
        try {
            openIfListener()
        } catch (e: FailClosed) {
            return
        }
        wire.kill()
        rows.close("close", 200)
    }

    private fun failClosedNow() {
        if (closed && rows.closeWritten) return
        closed = true
        wire.kill()
        rows.close("close:ledger-failure", 503)
    }
}
