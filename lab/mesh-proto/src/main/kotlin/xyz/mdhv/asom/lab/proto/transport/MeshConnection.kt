package xyz.mdhv.asom.lab.proto.transport

import java.io.InputStream
import java.io.OutputStream
import xyz.mdhv.asom.lab.proto.trust.Pin
import xyz.mdhv.asom.lab.proto.wire.ConnMode
import xyz.mdhv.asom.lab.proto.wire.PeerRole

/**
 * Raw network byte counts of one connection as the transport saw them (L-L15). [measured] is true only when the counts come from the
 * layer that actually moved the bytes (an SSLEngine wrap or unwrap result, or a record tap), false for the ESTIMATED form of LAB_SPEC 7.6.
 */
class TransportCounters(val networkBytesIn: Long, val networkBytesOut: Long, val measured: Boolean)

/**
 * What the session layer needs from a transport, and nothing more: two byte streams, who the peer is, and the byte counts. The TLS
 * transport implements it over an SSLEngine; session tests implement it over memory pipes. [peerPin] is the pin of the authenticated
 * peer (null on a pairing-mode connection before pairing has succeeded). Closing is idempotent and is a TLS close, never a GOAWAY.
 */
interface MeshConnection : AutoCloseable {
    val role: PeerRole
    val mode: ConnMode
    val input: InputStream
    val output: OutputStream
    val peerPin: Pin?
    fun counters(): TransportCounters
}
