package xyz.mdhv.asom.ut

import java.io.IOException
import java.nio.file.Path
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.ledger.JsonlReader
import xyz.mdhv.asom.lab.ledger.JsonlSink

/** Where node frames go. */
fun interface FrameSink {
    fun send(frame: NodeFrame)
}

/**
 * The requester pipeline behind `borrow`. UT-0 has none (there is no mesh transport yet, only the lab's shells), so the
 * default implementation answers with the error a peers-only universe with no usable peer gives.
 */
interface Requester {
    fun start(request: UiFrame.Borrow, sink: FrameSink)
    fun cancel(rid: String)
    fun cancelAll()
    fun closeSessions(closedBy: String)
    fun writeInterruptedRows(attemptIds: List<String>)
}

object NoRequester : Requester {
    override fun start(request: UiFrame.Borrow, sink: FrameSink) {
        sink.send(NodeFrame.Error(request.rid, UiErrorCode.NO_PROVIDER_KEY))
    }

    override fun cancel(rid: String) {}
    override fun cancelAll() {}
    override fun closeSessions(closedBy: String) {}
    override fun writeInterruptedRows(attemptIds: List<String>) {}
}

/** The node's own ledger file. Opening it is the fail-closed step of STARTING (FC-1). */
interface LedgerPort {
    fun rows(since: Long, limit: Int): List<JObject>
    fun close()
}

class FileLedger private constructor(private val path: Path, private val sink: JsonlSink) : LedgerPort {
    override fun rows(since: Long, limit: Int): List<JObject> {
        val read = JsonlReader.read(path)
        return read.rows.filter { it.ts >= since }.take(limit).map { it.toRow() }
    }

    override fun close() = sink.close()

    companion object {
        fun open(file: Path): FileLedger? = try {
            java.nio.file.Files.createDirectories(file.parent)
            FileLedger(file, JsonlSink(file))
        } catch (e: java.io.IOException) {
            null
        }
    }
}

object ExitStatus {
    const val OK = 0
    const val PROTOCOL_VIOLATION = 65
    const val IO_ERROR = 74
}

/**
 * One UI session on `asom-ut-ctl/1`: `hello`, then any frames of the closed set, until `shutdown` or end of input. Anything
 * outside the set (a bad line, a line over 1 MiB, an unknown type, a second `hello`) ends the session with a fixed code on
 * stderr and no echo of the line (fail closed; ERRATA ERR-UT-CTL-1). Every method that touches state holds one lock, so the
 * watchdog thread and the reader cannot interleave.
 */
class NodeSession(
    private val reader: LineReader,
    private val out: FrameSink,
    private val lifecycle: NodeLifecycle,
    private val diag: Diag,
    private val openLedger: () -> LedgerPort?,
    private val selfTest: () -> JObject,
    private val requester: Requester = NoRequester,
    private val peers: () -> List<PeerView> = { emptyList() },
    private val catalogueModels: Set<String>? = null,
    private val nodeTag: String = "none",
    private val keyStorage: String = "unknown",
) {
    private val lock = Any()
    private var ledger: LedgerPort? = null
    private var lastState: Pair<String, Long>? = null
    private var greeted = false

    fun run(): Int {
        try {
            val hello = when (val first = reader.next()) {
                Line.Eof -> return ExitStatus.OK
                Line.TooLong -> return violate(BadFrame.TOO_LONG)
                is Line.Data -> FrameCodec.decodeUi(first.bytes)
            }
            if (hello is Decoded.Bad) return violate(hello.reason)
            if ((hello as Decoded.Ok<*>).frame != UiFrame.Hello(CtlProtocol.VERSION)) return violate(BadFrame.BAD_FIELD)
            synchronized(lock) {
                out.send(NodeFrame.HelloAck(nodeTag, keyStorage))
                greeted = true
                ledger = openLedger()
                if (ledger != null) {
                    settle(lifecycle.apply(LcEvent.LedgerReady))
                } else {
                    diag.emit(DiagCode.LEDGER_UNAVAILABLE)
                    settle(lifecycle.apply(LcEvent.LedgerFailed))
                }
                emitState()
            }
            while (true) {
                when (val line = reader.next()) {
                    Line.Eof -> return finish(ExitStatus.OK)
                    Line.TooLong -> return violate(BadFrame.TOO_LONG)
                    is Line.Data -> when (val d = FrameCodec.decodeUi(line.bytes)) {
                        is Decoded.Bad -> return violate(d.reason)
                        is Decoded.Ok -> {
                            val exit = synchronized(lock) { handle(d.frame) }
                            if (exit != null) return if (exit == ExitStatus.OK) finish(ExitStatus.OK) else violate(BadFrame.UNKNOWN_TYPE)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            return finish(ExitStatus.IO_ERROR, quiet = true)
        }
    }

    /** One watchdog tick (the plugin's 1 s heartbeat covers the UI side; this covers the node side, UA03). */
    fun tick() = synchronized(lock) {
        settle(lifecycle.apply(LcEvent.Tick))
        emitState()
    }

    private fun violate(reason: BadFrame): Int {
        diag.emit(DiagCode.PROTOCOL_VIOLATION, reason)
        return finish(ExitStatus.PROTOCOL_VIOLATION, quiet = true)
    }

    private fun finish(code: Int, quiet: Boolean = false): Int {
        synchronized(lock) {
            if (!lifecycle.stopped) {
                try {
                    settle(lifecycle.apply(LcEvent.Shutdown))
                } catch (e: IOException) {
                    if (!quiet) return ExitStatus.IO_ERROR
                }
            }
            ledger?.close()
        }
        return code
    }

    /** Null to go on; [ExitStatus.OK] when the UI asked to stop; [ExitStatus.PROTOCOL_VIOLATION] for a frame that is not allowed here. */
    private fun handle(frame: UiFrame): Int? {
        when (frame) {
            is UiFrame.Hello -> return ExitStatus.PROTOCOL_VIOLATION
            is UiFrame.Lifecycle -> settle(lifecycle.apply(LcEvent.Ui(frame.state)))
            is UiFrame.Borrow -> borrow(frame)
            is UiFrame.Cancel -> requester.cancel(frame.rid)
            is UiFrame.Peers -> peersScreen(frame)
            is UiFrame.Pair -> out.send(NodeFrame.Error(null, UiErrorCode.UNSUPPORTED_BY_DRIVER))
            is UiFrame.Revoke -> out.send(NodeFrame.Error(null, UiErrorCode.UNSUPPORTED_BY_DRIVER))
            is UiFrame.Export -> out.send(NodeFrame.Error(null, UiErrorCode.UNSUPPORTED_BY_DRIVER))
            is UiFrame.LedgerQuery -> {
                val l = ledger
                if (lifecycle.state == NodeState.LEDGER_FAIL || l == null) out.send(NodeFrame.Error(null, UiErrorCode.LEDGER_UNAVAILABLE))
                else out.send(NodeFrame.Rows(l.rows(frame.since, frame.limit.toInt())))
            }
            UiFrame.SelfTest -> out.send(NodeFrame.SelfTestResult(selfTest()))
            UiFrame.Shutdown -> {
                settle(lifecycle.apply(LcEvent.Shutdown))
                emitState()
                return ExitStatus.OK
            }
        }
        emitState()
        return null
    }

    private fun borrow(req: UiFrame.Borrow) {
        if (lifecycle.state == NodeState.LEDGER_FAIL || lifecycle.state == NodeState.STARTING) {
            out.send(NodeFrame.Error(req.rid, UiErrorCode.LEDGER_UNAVAILABLE))
            return
        }
        val error = ErrorMapping.map(req.model, peers(), catalogueModels)
        if (error != null) {
            out.send(NodeFrame.Error(req.rid, error))
            return
        }
        val step = lifecycle.apply(LcEvent.RequestArrived(req.rid))
        settle(step)
        if (step.refused != null) out.send(NodeFrame.Error(req.rid, step.refused)) else requester.start(req, out)
    }

    private fun peersScreen(frame: UiFrame.Peers) {
        if (!frame.open) {
            settle(lifecycle.apply(LcEvent.PeersClosed))
            return
        }
        val step = lifecycle.apply(LcEvent.PeersOpened)
        settle(step)
        if (step.refused != null) out.send(NodeFrame.Error(null, step.refused))
        else out.send(NodeFrame.PeersList(peers().map { JObject(listOf("alias" to JString(UiProjection.sanitizeAlias(it.alias)))) }))
    }

    /**
     * Executes the effects and answers the drain and resume phases at once: this host has no work that takes time. Each stop
     * is announced, so the UI sees `interrupted` before the node goes back to `idle` (ubuntu-touch.md 3.5).
     */
    private fun settle(first: LcStep) {
        var step = first
        while (true) {
            execute(step.effects)
            emitState()
            step = when (step.state) {
                NodeState.FREEZING -> lifecycle.apply(LcEvent.Drained)
                NodeState.RESUMING -> lifecycle.apply(LcEvent.Resumed)
                else -> return
            }
        }
    }

    private fun execute(effects: List<LcEffect>) {
        for (e in effects) when (e) {
            LcEffect.CancelAttempts -> requester.cancelAll()
            is LcEffect.CloseSessions -> requester.closeSessions(e.closedBy)
            is LcEffect.WriteInterruptedRows -> requester.writeInterruptedRows(e.attemptIds)
            LcEffect.HoldDisplay, LcEffect.ReleaseDisplay, LcEffect.ForceLedger, LcEffect.ExitNow -> {}
        }
    }

    private fun emitState() {
        if (!greeted) return
        val now = lifecycle.wireState() to lifecycle.sessions.toLong()
        if (now == lastState) return
        lastState = now
        out.send(NodeFrame.State(now.first, now.second, NodeStates.LENDING_OFF))
    }
}
