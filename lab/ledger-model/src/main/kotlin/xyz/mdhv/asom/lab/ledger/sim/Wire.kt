package xyz.mdhv.asom.lab.ledger.sim

import java.util.SplittableRandom
import xyz.mdhv.asom.lab.ledger.FrameSpec
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.ledger.LedgerWriteException
import xyz.mdhv.asom.lab.ledger.OverheadBasis
import xyz.mdhv.asom.lab.ledger.PeerPath
import xyz.mdhv.asom.lab.ledger.RowSink
import xyz.mdhv.asom.lab.ledger.TransportMeter
import xyz.mdhv.asom.lab.ledger.WireOut
import xyz.mdhv.asom.lab.ledger.ceilDiv
import xyz.mdhv.asom.lab.ledger.laws.AppendFailed
import xyz.mdhv.asom.lab.ledger.laws.Appended
import xyz.mdhv.asom.lab.ledger.laws.Closed
import xyz.mdhv.asom.lab.ledger.laws.ConnFacts
import xyz.mdhv.asom.lab.ledger.laws.PlainTap
import xyz.mdhv.asom.lab.ledger.laws.Received
import xyz.mdhv.asom.lab.ledger.laws.Sent
import xyz.mdhv.asom.lab.ledger.laws.Tap
import xyz.mdhv.asom.lab.ledger.laws.Trace

/**
 * In-memory stand-ins used by the lab's ledger tests and by the L01/L02 vectors. Nothing here opens a socket or a file: the "network" is a set of
 * counters, and the "TLS engine" is the record arithmetic of RFC 8446 (5-byte header, 1-byte inner type, 16-byte tag, at most 2^14 plaintext bytes
 * per record). It is a MODEL: it proves the ledger's accounting logic, not any real TLS stack (evidence label LAB).
 */
class ProcessDeath : RuntimeException("simulated process death")

/** The peer closed the connection: a later `send` fails like a write on a closed socket. */
class ConnectionClosed : java.io.IOException("connection closed")

/** Death at step k: every instrumented action (append, send, delivery, engine read) ticks, and the k-th tick throws before the action's effect. */
class SimKill(var remaining: Int? = null) {
    var steps: Int = 0
        private set

    fun tick() {
        steps++
        val r = remaining ?: return
        if (r == 0) throw ProcessDeath()
        remaining = r - 1
    }
}

class SimClock {
    private var t = 1_000_000L

    fun now(): Long {
        t += 3
        return t
    }
}

/** Records every append that returned or threw, in order. It sits outside the ledger code. */
class TracingSink(private val node: String, private val inner: RowSink, private val trace: Trace, private val kill: SimKill) : RowSink {
    override fun append(row: LabRouteRecord) {
        kill.tick()
        try {
            inner.append(row)
        } catch (e: LedgerWriteException) {
            trace.add(AppendFailed(node, row))
            throw e
        }
        trace.add(Appended(node, row))
    }
}

/** How a lying or buggy engine misreports what it measured. Used only by mutation tests of law L-L15. */
enum class EngineFault { NONE, WRAP_REPORTS_ONE_LESS, IGNORES_ALERTS }

/** What an `SSLEngine` reports through its `wrap` and `unwrap` results. It never reads the row writer. */
class SimMeter(private val fault: EngineFault) : TransportMeter {
    override val basis: OverheadBasis = OverheadBasis.MEASURED
    private var network = 0L
    private var plaintext = 0L
    private var handshake = 0L

    fun onHandshake(bytes: Long) {
        network += bytes
        handshake += bytes
    }

    fun onWrap(plainConsumed: Long, produced: Long) {
        network += if (fault == EngineFault.WRAP_REPORTS_ONE_LESS) produced - 1 else produced
        plaintext += plainConsumed
    }

    fun onUnwrap(consumed: Long, plainProduced: Long) {
        network += consumed
        plaintext += plainProduced
    }

    fun onAlerts(bytes: Long) {
        if (fault != EngineFault.IGNORES_ALERTS) network += bytes
    }

    override fun networkBytes(): Long = network

    override fun plaintextBytes(): Long = plaintext

    override fun handshakeNetworkBytes(): Long = handshake
}

class SimConnection(
    private val trace: Trace,
    private val kill: SimKill,
    private val rng: SplittableRandom,
    val nodeA: String,
    val nodeB: String?,
    private val measured: Boolean,
    fault: EngineFault,
    val expectedPath: PeerPath?,
) {
    var rawBytes: Long = 0
        private set
    var sidA: String = ""
    var sidB: String? = null
    private val meterA = SimMeter(fault)
    private val meterB = SimMeter(EngineFault.NONE)
    private val plainA = Plain()
    private val plainB = Plain()
    private val closedBy = HashSet<String>()
    private var pendingPayload: String? = null

    private class Plain {
        var out = 0L
        var inn = 0L
        var flushOut = 0
        var flushIn = 0
    }

    val endA: WireOut = End(isA = true)
    val endB: WireOut = End(isA = false)

    /** Handshake flights (client and server): counted by the socket tap and by each engine's meter, before any application byte. */
    fun handshake() {
        val c = 1_500L + rng.nextInt(1_200)
        val s = 2_400L + rng.nextInt(1_500)
        rawBytes += c + s
        meterA.onHandshake(c + s)
        meterB.onHandshake(c + s)
    }

    fun setPayload(text: String?) {
        pendingPayload = text
    }

    /** A hostile node with no ledger sends a frame to A: the tap and A's engine see it, no `Sent` is recorded for it. */
    fun inject(frame: FrameSpec) {
        kill.tick()
        val n = 9L + frame.payloadLen
        val produced = n + 22 * ceilDiv(n, 16_384)
        rawBytes += produced
        plainA.inn += n
        plainA.flushIn++
        meterA.onUnwrap(produced, n)
    }

    fun deliver(toA: Boolean, frame: FrameSpec) {
        kill.tick()
        val (node, sid) = if (toA) nodeA to sidA else nodeB!! to sidB!!
        trace.add(Received(node, sid, frame))
    }

    private inner class End(val isA: Boolean) : WireOut {
        override val meter: TransportMeter? get() = if (!measured) null else if (isA) meterA else meterB

        override fun send(frame: FrameSpec) {
            kill.tick()
            if (closedBy.isNotEmpty()) throw ConnectionClosed()
            val n = 9L + frame.payloadLen
            val produced = n + 22 * ceilDiv(n, 16_384)
            rawBytes += produced
            val mine = if (isA) plainA else plainB
            val other = if (isA) plainB else plainA
            mine.out += n
            mine.flushOut++
            other.inn += n
            other.flushIn++
            (if (isA) meterA else meterB).onWrap(n, produced)
            (if (isA) meterB else meterA).onUnwrap(produced, n)
            val node = if (isA) nodeA else nodeB!!
            val sid = if (isA) sidA else sidB!!
            trace.add(Sent(node, sid, frame, pendingPayload))
            pendingPayload = null
        }

        override fun close() {
            kill.tick()
            val node = if (isA) nodeA else (nodeB ?: "hostile")
            val sid = if (isA) sidA else (sidB ?: "")
            if (!closedBy.add(node)) return
            if (nodeB == null && !isA) {
                if (closedBy.size == 1) {
                    rawBytes += 48
                    meterA.onAlerts(48)
                }
                return
            }
            if (closedBy.size == 1) {
                rawBytes += 48
                meterA.onAlerts(48)
                meterB.onAlerts(48)
            }
            trace.add(Closed(node, sid))
        }
    }

    /** Called once both ends closed: what the socket tap and the plaintext tap counted, and the ground truth of the interface. */
    fun finalizeTaps() {
        trace.add(Tap(nodeA, sidA, rawBytes))
        trace.add(PlainTap(nodeA, sidA, plainA.out, plainA.inn, plainA.flushOut, plainA.flushIn))
        trace.add(ConnFacts(nodeA, sidA, expectedPath, measured))
        if (nodeB != null && sidB != null) {
            trace.add(Tap(nodeB, sidB!!, rawBytes))
            trace.add(PlainTap(nodeB, sidB!!, plainB.out, plainB.inn, plainB.flushOut, plainB.flushIn))
            trace.add(ConnFacts(nodeB, sidB!!, expectedPath, measured))
        }
    }
}
