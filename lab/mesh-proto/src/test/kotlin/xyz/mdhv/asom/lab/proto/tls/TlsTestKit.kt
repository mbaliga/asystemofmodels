package xyz.mdhv.asom.lab.proto.tls

import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.PrivateKey
import java.time.Instant
import java.util.SplittableRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import xyz.mdhv.asom.lab.manifest.EcKeyPair
import xyz.mdhv.asom.lab.manifest.Es256
import xyz.mdhv.asom.lab.manifest.TestOnlyKeys
import xyz.mdhv.asom.lab.policy.PeerStatus
import xyz.mdhv.asom.lab.proto.trust.CertTemplates
import xyz.mdhv.asom.lab.proto.trust.CertTemplatesTest
import xyz.mdhv.asom.lab.proto.trust.InMemoryPeerStore
import xyz.mdhv.asom.lab.proto.trust.PairingCeremony
import xyz.mdhv.asom.lab.proto.trust.PeerClass
import xyz.mdhv.asom.lab.proto.trust.PeerRegistry
import xyz.mdhv.asom.lab.proto.trust.Pin
import xyz.mdhv.asom.lab.proto.trust.PinImport
import xyz.mdhv.asom.lab.proto.trust.RegistryResult
import xyz.mdhv.asom.lab.proto.trust.Scope

/** Loopback sockets for tests, bound to 127.0.0.1 with an ephemeral port (LAB_SPEC R5: sockets only inside tests, never a wildcard address). */
object Loop {
    val ADDRESS: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    /** A connected pair: first the dialling side, then the accepted side. */
    fun pair(): Pair<SocketChannel, SocketChannel> {
        ServerSocketChannel.open().use { server ->
            server.bind(InetSocketAddress(ADDRESS, 0))
            val client = SocketChannel.open(server.localAddress)
            val accepted = server.accept()
            client.socket().tcpNoDelay = true
            accepted.socket().tcpNoDelay = true
            return client to accepted
        }
    }
}

/** What one side of a test connection ended with. */
sealed interface End<out T> {
    class Ok<T>(val value: T) : End<T>
    class Failed(val error: Throwable) : End<Nothing>
}

val <T> End<T>.value: T get() = (this as End.Ok<T>).value
val End<*>.refusal: MeshTlsException get() = (this as End.Failed).error as MeshTlsException

/** Runs [a] and [b] on their own threads and waits; a hang is a test failure, not a stuck build. */
fun <A, B> both(a: () -> A, b: () -> B, timeoutSec: Long = 40): Pair<End<A>, End<B>> {
    val ra = AtomicReference<End<A>>()
    val rb = AtomicReference<End<B>>()
    val done = CountDownLatch(2)
    fun <T> launch(name: String, f: () -> T, out: AtomicReference<End<T>>) = Thread {
        try {
            out.set(End.Ok(f()))
        } catch (t: Throwable) {
            out.set(End.Failed(t))
        } finally {
            done.countDown()
        }
    }.also { it.name = name; it.isDaemon = true; it.start() }
    launch("test-a", a, ra)
    launch("test-b", b, rb)
    check(done.await(timeoutSec, TimeUnit.SECONDS)) { "a test peer did not finish within $timeoutSec s" }
    return ra.get() to rb.get()
}

/** A node of the test mesh: a node key (NIK), a leaf key, and the two certificates of trust.md 2.4. */
class TestNode(val label: String, val nik: EcKeyPair, val leafKey: EcKeyPair, val nodeCert: ByteArray, val leafCert: ByteArray) {
    val pin: Pin = (Pin.fromSpki(nik.spki, productionKeys = false) as PinImport.Ok).pin

    fun identity() = MeshIdentity(nodeCert, leafCert, leafKey.private)

    companion object {
        val NOW: Instant = Instant.now()

        fun serial(rnd: SplittableRandom): ByteArray = CertTemplatesTest.serial(rnd)

        fun create(label: String, nik: EcKeyPair, rnd: SplittableRandom = SplittableRandom(label.hashCode().toLong()), nowSec: Long = NOW.epochSecond): TestNode {
            val leafKey = Es256.generate()
            val node = CertTemplates.nodeCertificate(nik.private, nik.spki, serial(rnd), nowSec - 86_400)
            val leaf = CertTemplates.leafCertificate(nik.private, nik.spki, leafKey.spki, serial(rnd), nowSec)
            return TestNode(label, nik, leafKey, node, leaf)
        }

        /** The TEST-ONLY keys (published private scalars) are the identity keys of the honest nodes in every test. */
        fun testOnly(name: String): TestNode = create(name, TestOnlyKeys.key(name).keyPair())

        fun fresh(label: String): TestNode = create(label, Es256.generate())
    }
}

/** A registry with whom a node is paired, built through the real [PeerRegistry] so a status change goes through its state machine. */
class TestRegistry {
    val store = InMemoryPeerStore()
    val registry = PeerRegistry(store)

    fun pair(pin: Pin): TestRegistry {
        val c = PairingCeremony(pin, "peer", "linux", PeerClass.OWN, ByteArray(32) { 1 }, setOf(Scope.INFER))
        c.localApprove()
        c.remoteApprove()
        check(registry.commitPairing(c, 1) is RegistryResult.Changed) { "pairing a test peer failed" }
        return this
    }

    fun suspend(pin: Pin): TestRegistry {
        check(registry.pause(pin, 2) is RegistryResult.Changed)
        return this
    }

    fun revoke(pin: Pin): TestRegistry {
        check(registry.revoke(pin, 3) is RegistryResult.Changed)
        return this
    }

    fun env(productionKeys: Boolean = false, windowOpen: Boolean = false, nowSec: Long = TestNode.NOW.epochSecond): MeshTlsEnv =
        MeshTlsEnv(registry.asStatusSource(), { Instant.ofEpochSecond(nowSec) }, { windowOpen }, productionKeys)

    fun status(pin: Pin): PeerStatus? = (registry.statusLookup(pin) as? xyz.mdhv.asom.lab.proto.trust.StatusLookup.Known)?.status
}

