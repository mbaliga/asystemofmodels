package xyz.mdhv.asom.lab.proto.tls

import java.nio.channels.SocketChannel
import xyz.mdhv.asom.lab.proto.trust.ChainMode
import xyz.mdhv.asom.lab.proto.trust.Pin

/** One honest dial or accept: what it returned, what the managers decided, and what the record tap saw on this side's socket. */
class Attempt(val end: End<TlsMeshConnection>, val observer: HandshakeObserver, val tap: RecordTap)

/** An honest node of the test mesh: an identity and a registry. [productionKeys] is false because the identity keys are the TEST-ONLY keys. */
class HonestNode(val node: TestNode, val registry: TestRegistry = TestRegistry(), val productionKeys: Boolean = false) {
    val pin: Pin get() = node.pin

    fun env(windowOpen: Boolean = false) = registry.env(productionKeys, windowOpen)

    fun dial(ch: SocketChannel, expect: ChainMode, book: SessionIdBook = SessionIdBook(), identity: MeshIdentity = node.identity()): Attempt {
        val tap = RecordTap(SocketNet(ch), book)
        val observer = HandshakeObserver()
        val end = try {
            End.Ok(MeshTls.dial(tap, identity, expect, env(), observer))
        } catch (t: Throwable) {
            End.Failed(t)
        }
        return Attempt(end, observer, tap)
    }

    fun dialPaired(ch: SocketChannel, peer: Pin, book: SessionIdBook = SessionIdBook()) = dial(ch, ChainMode.ExpectPaired(peer), book)

    fun accept(ch: SocketChannel, windowOpen: Boolean = false, book: SessionIdBook = SessionIdBook()): Attempt {
        val tap = RecordTap(SocketNet(ch), book)
        val observer = HandshakeObserver()
        val end = try {
            End.Ok(MeshTls.accept(tap, node.identity(), env(windowOpen), observer))
        } catch (t: Throwable) {
            End.Failed(t)
        }
        return Attempt(end, observer, tap)
    }

    companion object {
        /** Two nodes that are paired with each other, on the TEST-ONLY keys key1 and key2. */
        fun pairedPair(): Pair<HonestNode, HonestNode> {
            val a = HonestNode(TestNode.testOnly("key1"))
            val b = HonestNode(TestNode.testOnly("key2"))
            a.registry.pair(b.pin)
            b.registry.pair(a.pin)
            return a to b
        }
    }
}
