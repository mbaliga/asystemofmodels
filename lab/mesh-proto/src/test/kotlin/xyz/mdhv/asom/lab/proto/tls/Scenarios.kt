package xyz.mdhv.asom.lab.proto.tls

import xyz.mdhv.asom.lab.proto.trust.ChainMode
import xyz.mdhv.asom.lab.proto.trust.Pin

/** One encounter between an honest mesh node and a hostile one, with both sides' records. */
class Duel(val honest: Attempt, val hostile: HostileResult, val hostileTap: RecordTap) {
    val honestEstablished: Boolean get() = honest.end is End.Ok
    val refusal: MeshTlsException get() = honest.end.refusal
}

object Scenarios {
    private fun release(at: Attempt, writeByte: Boolean) {
        val conn = (at.end as? End.Ok)?.value ?: return
        try {
            if (writeByte) conn.output.write(1)
        } catch (e: Exception) {
            // the hostile side may already be gone; the outcome is judged on what the honest side returned
        } finally {
            runCatching { conn.close() }
        }
    }

    /** The hostile node is the TLS server and the honest node dials it, expecting [expect]. */
    fun hostileServer(honest: HonestNode, expect: ChainMode, spec: HostileSpec, book: SessionIdBook = SessionIdBook()): Duel {
        val (cc, sc) = Loop.pair()
        val hostileTap = RecordTap(SocketNet(sc))
        val (h, x) = both(
            { honest.dial(cc, expect, book).also { release(it, writeByte = true) } },
            { Hostile.run(hostileTap, client = false, spec = spec) },
        )
        return Duel(h.value, x.value, hostileTap)
    }

    /** The hostile node is the TLS client and dials the honest listener. */
    fun hostileClient(honest: HonestNode, spec: HostileSpec, windowOpen: Boolean = false, book: SessionIdBook = SessionIdBook()): Duel {
        val (cc, sc) = Loop.pair()
        val hostileTap = RecordTap(SocketNet(cc))
        val (h, x) = both(
            { honest.accept(sc, windowOpen, book).also { release(it, writeByte = true) } },
            { Hostile.run(hostileTap, client = true, spec = spec) },
        )
        return Duel(h.value, x.value, hostileTap)
    }

    /** A raw ClientHello sent to the honest listener; the honest side is left to fail when the peer goes away. */
    fun rawHello(honest: HonestNode, hello: ByteArray, follow: ByteArray? = null): Duel {
        val (cc, sc) = Loop.pair()
        val hostileTap = RecordTap(SocketNet(cc))
        val (h, x) = both(
            { honest.accept(sc).also { release(it, writeByte = false) } },
            {
                val buf = java.nio.ByteBuffer.allocate(8192)
                hostileTap.write(java.nio.ByteBuffer.wrap(hello), 5000)
                follow?.let { hostileTap.write(java.nio.ByteBuffer.wrap(it), 5000) }
                var read = 0
                val deadline = System.nanoTime() + 1_500_000_000L
                while (hostileTap.serverHellos.isEmpty() && System.nanoTime() < deadline) {
                    buf.clear()
                    val n = try {
                        hostileTap.read(buf, 300)
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    } catch (e: java.io.IOException) {
                        break
                    }
                    if (n < 0) break
                    read += n
                }
                hostileTap.close()
                read
            },
        )
        return Duel(h.value, HostileResult(false, null, null, null, 0, null, null, x.value), hostileTap)
    }

    fun pinOf(h: HonestNode): Pin = h.pin
}
