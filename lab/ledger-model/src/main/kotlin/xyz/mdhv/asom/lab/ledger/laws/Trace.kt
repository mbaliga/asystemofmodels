package xyz.mdhv.asom.lab.ledger.laws

import xyz.mdhv.asom.lab.ledger.FrameSpec
import xyz.mdhv.asom.lab.ledger.LabEgress
import xyz.mdhv.asom.lab.ledger.LabRouteRecord

/**
 * What actually happened, recorded by instruments that sit OUTSIDE the ledger code (the sink wrapper, the wire, the socket tap and the
 * scripted "application"), so that every law is an oracle over ground truth and not over the implementation's own bookkeeping.
 */
sealed interface Ev {
    val node: String
}

/** A row became durable: `append` returned. */
data class Appended(override val node: String, val row: LabRouteRecord) : Ev

/** `append` threw. The row may or may not be in the file (AFTER_WRITE), and the caller must act as if it is not durable. */
data class AppendFailed(override val node: String, val row: LabRouteRecord) : Ev

/** A frame's bytes were handed to the TLS engine. [payloadText] is the JSON payload when the frame has one (raw bytes otherwise). */
data class Sent(override val node: String, val sessionId: String, val frame: FrameSpec, val payloadText: String?) : Ev

/** A frame arrived and was delivered to the receiving node's ledger layer. */
data class Received(override val node: String, val sessionId: String, val frame: FrameSpec) : Ev

/** A TCP connect (the SYN) was issued. */
data class Syn(override val node: String, val sessionId: String) : Ev

/** The lender's engine read a request body. */
data class EngineRead(override val node: String, val attemptId: String) : Ev

/** The TLS connection and the socket were closed by this node. */
data class Closed(override val node: String, val sessionId: String) : Ev

/** Content left the device for a class of destination, in the scripted request [requestId] (attempt [attemptId]). */
data class ContentSent(override val node: String, val requestId: String, val attemptId: String, val cls: LabEgress) : Ev

/** Ground truth of who served a request: the class of the serving attempt, or null when nothing served. */
data class ServedBy(override val node: String, val requestId: String, val cls: LabEgress?) : Ev

/** What the app-facing API answered for a request (its echo headers). */
data class Responded(override val node: String, val requestId: String, val headers: Map<String, String>) : Ev

/** The raw bytes the socket tap counted for one end of a connection, both directions, after both ends closed. */
data class Tap(override val node: String, val sessionId: String, val rawBytes: Long) : Ev

/** The plaintext tap at the frame codec: `9 + payload` per frame, counted by the tap itself, never by the row writer. */
data class PlainTap(override val node: String, val sessionId: String, val outBytes: Long, val inBytes: Long, val flushesOut: Int, val flushesIn: Int) : Ev

/** Ground truth of a connection: the interface its socket was bound to, and the peer path the rules give for it. */
data class ConnFacts(override val node: String, val sessionId: String, val expectedPath: xyz.mdhv.asom.lab.ledger.PeerPath?, val measured: Boolean) : Ev

/** A fuzzed request body, the secrets the ledger must never hold, and the display name of a device (law L-L8). */
data class Secrets(override val node: String, val bodies: List<String>, val keys: List<String>, val displayNames: List<String>) : Ev

class Trace {
    private val list = ArrayList<Ev>()
    val events: List<Ev> get() = list

    fun add(e: Ev) {
        list += e
    }
}
