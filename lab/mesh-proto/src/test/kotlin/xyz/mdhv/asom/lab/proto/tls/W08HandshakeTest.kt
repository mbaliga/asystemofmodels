package xyz.mdhv.asom.lab.proto.tls

import java.util.Collections
import java.util.SplittableRandom
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Timeout
import xyz.mdhv.asom.lab.proto.trust.ChainMode
import xyz.mdhv.asom.lab.proto.trust.ChainReject
import xyz.mdhv.asom.lab.proto.trust.ChainVerdict
import xyz.mdhv.asom.lab.proto.trust.LawCounters

enum class W08Kind {
    WRONG_CHAIN, MISSING_CHAIN, CA_LEAF, P384_KEY, RSA_KEY, SHA1_SIGNATURE, EXPIRED_LEAF, LEAF_SIGNED_BY_OTHER_NIK, NO_CLIENT_CERT, WRONG_ALPN, NO_ALPN,
    RESUMPTION_ATTEMPT, EARLY_DATA_ATTEMPT, TLS12_ONLY, REVOKED_PIN, SUSPENDED_PIN,
}

enum class W08Role { HOSTILE_SERVER, HOSTILE_CLIENT }

/** What the suite saw, shared by every test method of the class and summarised once at the end. */
object W08 {
    val cases = AtomicInteger()
    val acceptedBadChains = AtomicInteger()
    val honestClientHellos = AtomicInteger()
    val honestPskClientHellos = AtomicInteger()
    val hostilePskClientHellos = AtomicInteger()
    val hostileEarlyDataClientHellos = AtomicInteger()
    val establishedSessions = AtomicInteger()
    val sessionsWithClientCertVerify = AtomicInteger()
    val exercised: MutableSet<String> = Collections.synchronizedSet(TreeSet())
    val refusals: MutableMap<String, Int> = Collections.synchronizedMap(TreeMap())
    val lines: MutableList<String> = Collections.synchronizedList(ArrayList())

    fun required(): Set<String> {
        val s = TreeSet<String>()
        for (k in W08Kind.entries) for (r in W08Role.entries) {
            if (k == W08Kind.NO_CLIENT_CERT && r == W08Role.HOSTILE_SERVER) continue
            s += "$k/$r"
        }
        return s
    }
}

/**
 * W08, the handshake half (LAB_SPEC 7.4): a hostile node in both roles against the honest mesh transport, on loopback 127.0.0.1 only. Expected: zero
 * accepted bad chains, no `pre_shared_key` in any ClientHello of the honest dialler (inspected with the record tap), a verified client certificate in
 * every established session, every refusal typed. Evidence label: LAB, oracle: self, NOT DEVICE EVIDENCE.
 */
@Timeout(600)
class W08HandshakeTest {
    companion object {
        val laws = LawCounters("w08-handshake")
        private val windows = System.getProperty("os.name").lowercase().startsWith("windows")

        @JvmStatic
        @AfterAll
        fun done() {
            val missing = W08.required() - W08.exercised
            W08.lines.sorted().forEach { println(it) }
            println("  w08 refusal kinds: ${W08.refusals}")
            laws.finish(setOf("typed-refusal", "alert-uniform", "client-verifies-server-first", "record-tap-detects-psk", "record-tap-detects-early-data", "client-cert-verify"))
            assertTrue(W08.cases.get() > 0, "W08 ran no hostile case")
            assertEquals(emptySet(), missing, "W08: hostile kinds (kind/role) that were never exercised")
            assertEquals(0, W08.acceptedBadChains.get(), "W08: a bad chain was accepted")
            assertTrue(W08.honestClientHellos.get() > 0, "W08 looked at no ClientHello of an honest dialler")
            assertTrue(W08.hostilePskClientHellos.get() >= 1, "the record tap never saw a hostile pre_shared_key: it cannot be trusted to see one")
            assertTrue(W08.hostileEarlyDataClientHellos.get() >= 1, "the record tap never saw a hostile early_data extension")
            assertEquals(W08.establishedSessions.get(), W08.sessionsWithClientCertVerify.get(), "W08: an established session without a verified client certificate")
            assertTrue(W08.establishedSessions.get() > 0, "W08 established no session to prove a client CertificateVerify on")
            assertEquals(0, W08.honestPskClientHellos.get(), "W08: an honest dialler sent pre_shared_key")
            println(
                "W08-handshake: accepted bad chains: ${W08.acceptedBadChains.get()}; ClientHellos with pre_shared_key: ${W08.honestPskClientHellos.get()}; " +
                    "sessions with client CertificateVerify: ${W08.sessionsWithClientCertVerify.get()}/${W08.establishedSessions.get()}",
            )
        }
    }

    private val rnd = SplittableRandom(808)
    private val now get() = TestNode.NOW.epochSecond

    private class Expected(val refusal: MeshTlsRefusal, val chain: ChainReject? = null, val alert: TlsAlert? = null) {
        fun matches(e: MeshTlsException) = e.refusal == refusal && (chain == null || e.chainReject == chain) && (alert == null || e.peerAlert == alert)
        override fun toString() = "$refusal${chain?.let { "/$it" } ?: ""}${alert?.let { "/$it" } ?: ""}"
    }

    private fun chainRejected(code: ChainReject) = Expected(MeshTlsRefusal.PEER_CHAIN_REJECTED, chain = code)

    private fun record(kind: W08Kind, role: W08Role, label: String, d: Duel, allowed: List<Expected>) {
        W08.cases.incrementAndGet()
        W08.exercised += "$kind/$role"
        val hello = if (role == W08Role.HOSTILE_SERVER) d.honest.tap.clientHellos else d.hostileTap.clientHellos
        if (role == W08Role.HOSTILE_SERVER) {
            W08.honestClientHellos.addAndGet(hello.size)
            W08.honestPskClientHellos.addAndGet(hello.count { it.hasPsk })
            assertTrue(hello.isNotEmpty(), "$kind/$role $label: the tap saw no ClientHello of the honest dialler")
        } else {
            W08.hostilePskClientHellos.addAndGet(hello.count { it.hasPsk })
            W08.hostileEarlyDataClientHellos.addAndGet(hello.count { it.hasEarlyData })
        }
        if (d.honestEstablished) {
            W08.acceptedBadChains.incrementAndGet()
            W08.lines += "W08-case | jdk ${Matrix.jdk} | $kind | $role | $label | ACCEPTED (BAD)"
            error("$kind/$role $label: the honest side established a session with a hostile node")
        }
        val e = d.refusal
        W08.refusals.merge(e.refusal.name, 1, Int::plus)
        laws.bump("typed-refusal")
        assertTrue(allowed.any { it.matches(e) }, "$kind/$role $label: got ${e.refusal}/${e.chainReject}/${e.peerAlert}, allowed ${allowed.joinToString(" or ")}")
        if (e.refusal == MeshTlsRefusal.PEER_CHAIN_REJECTED) {
            val seen = Hostile.alertOf(d.hostile.handshakeError) ?: d.hostile.alertAfterHandshake
            if (seen != null) {
                assertEquals(TlsAlert.CERTIFICATE_UNKNOWN, seen, "$kind/$role $label: every chain refusal is the same alert")
                laws.bump("alert-uniform")
            } else {
                val ownFailure = d.hostile.handshakeError is javax.net.ssl.SSLException
                assertTrue(
                    ownFailure || windows,
                    "$kind/$role $label: the refused peer saw no alert, and its own engine did not fail first (a reset is tolerated on Windows only): " +
                        "established=${d.hostile.established} hsErr=${d.hostile.handshakeError} readErr=${d.hostile.readError}",
                )
                laws.bump("hostile-failed-before-alert")
            }
            if (role == W08Role.HOSTILE_SERVER) {
                assertEquals(0, d.hostile.trustCalls, "$kind/$role $label: the dialler refused the server before it sent its own certificate")
                laws.bump("client-verifies-server-first")
            }
        }
        W08.lines += "W08-case | jdk ${Matrix.jdk} | $kind | $role | $label | refused ${e.refusal}${e.chainReject?.let { "/$it" } ?: ""}${e.peerAlert?.let { "/$it" } ?: ""}"
    }

    private fun serverCase(kind: W08Kind, label: String, spec: (HonestNode, HonestNode) -> HostileSpec, vararg allowed: Expected, tweak: (HonestNode, HonestNode) -> Unit = { _, _ -> }) {
        val (a, b) = HonestNode.pairedPair()
        tweak(a, b)
        record(kind, W08Role.HOSTILE_SERVER, label, Scenarios.hostileServer(a, ChainMode.ExpectPaired(b.pin), spec(a, b)), allowed.toList())
    }

    private fun clientCase(kind: W08Kind, label: String, spec: (HonestNode, HonestNode) -> HostileSpec, vararg allowed: Expected, tweak: (HonestNode, HonestNode) -> Unit = { _, _ -> }) {
        val (a, b) = HonestNode.pairedPair()
        tweak(a, b)
        record(kind, W08Role.HOSTILE_CLIENT, label, Scenarios.hostileClient(b, spec(a, b)), allowed.toList())
    }

    /** One certificate fault, in both roles. [serverAlt] and [clientAlt] are the other typed outcomes a JDK may produce before the verifier is reached. */
    private fun defect(kind: W08Kind, build: (TestNode) -> HostileChain, code: ChainReject, serverAlt: List<Expected> = emptyList(), clientAlt: List<Expected> = emptyList()) {
        serverCase(kind, "hostile server chain", { _, b -> HostileSpec(build(b.node)) }, chainRejected(code), *serverAlt.toTypedArray())
        clientCase(kind, "hostile client chain", { a, _ -> HostileSpec(build(a.node)) }, chainRejected(code), *clientAlt.toTypedArray())
    }

    // ------------------------------------------------------------------------------------------- chains

    @Test
    fun wrongChains() {
        // a valid chain of a node nobody paired (as server: not the pin the dialler expects; as client: not in the listener's registry)
        val stranger = TestNode.fresh("stranger")
        serverCase(W08Kind.WRONG_CHAIN, "valid chain, other pin", { _, _ -> HostileSpec(HostileCerts.unpaired(stranger)) }, chainRejected(ChainReject.PIN_MISMATCH))
        clientCase(W08Kind.WRONG_CHAIN, "valid chain, unknown pin", { _, _ -> HostileSpec(HostileCerts.unpaired(stranger)) }, chainRejected(ChainReject.PIN_UNKNOWN))
        // the leaf of one node under the node certificate of another
        serverCase(W08Kind.WRONG_CHAIN, "leaf of one node under another node cert", { _, b -> HostileSpec(HostileCerts.mismatchedPair(b.node, stranger)) }, chainRejected(ChainReject.ISSUER_MISMATCH))
        clientCase(W08Kind.WRONG_CHAIN, "leaf of one node under another node cert", { a, _ -> HostileSpec(HostileCerts.mismatchedPair(a.node, stranger)) }, chainRejected(ChainReject.ISSUER_MISMATCH))
        // no node certificate at all
        serverCase(W08Kind.WRONG_CHAIN, "leaf only", { _, b -> HostileSpec(HostileCerts.leafOnly(b.node)) }, chainRejected(ChainReject.CHAIN_LENGTH))
        clientCase(W08Kind.WRONG_CHAIN, "leaf only", { a, _ -> HostileSpec(HostileCerts.leafOnly(a.node)) }, chainRejected(ChainReject.CHAIN_LENGTH))
    }

    @Test
    fun missingChains() {
        serverCase(
            W08Kind.MISSING_CHAIN, "server with nothing to present", { _, _ -> HostileSpec(HostileCerts.nothing()) },
            Expected(MeshTlsRefusal.PEER_ALERT, alert = TlsAlert.HANDSHAKE_FAILURE), Expected(MeshTlsRefusal.PEER_CLOSED),
        )
        clientCase(
            W08Kind.MISSING_CHAIN, "client with nothing to present", { _, _ -> HostileSpec(HostileCerts.nothing()) },
            Expected(MeshTlsRefusal.PEER_CERTIFICATE_MISSING),
        )
        clientCase(
            W08Kind.NO_CLIENT_CERT, "client with no key manager at all", { _, _ -> HostileSpec(HostileCerts.nothing(), noKeyManager = true) },
            Expected(MeshTlsRefusal.PEER_CERTIFICATE_MISSING),
        )
    }

    @Test
    fun certificateFaults() {
        defect(W08Kind.CA_LEAF, { HostileCerts.caLeaf(it, rnd, now) }, ChainReject.LEAF_IS_CA)
        defect(W08Kind.P384_KEY, { HostileCerts.p384Leaf(it, rnd, now) }, ChainReject.KEY_UNSUPPORTED)
        defect(
            W08Kind.RSA_KEY, { HostileCerts.rsaLeaf(it, rnd, now) }, ChainReject.KEY_UNSUPPORTED,
            serverAlt = listOf(Expected(MeshTlsRefusal.PEER_ALERT, alert = TlsAlert.HANDSHAKE_FAILURE)),
            clientAlt = listOf(Expected(MeshTlsRefusal.PEER_CERTIFICATE_MISSING)),
        )
        defect(W08Kind.SHA1_SIGNATURE, { HostileCerts.sha1Leaf(it, rnd, now) }, ChainReject.SIG_ALG_UNSUPPORTED)
        defect(W08Kind.EXPIRED_LEAF, { HostileCerts.expiredLeaf(it, rnd, now) }, ChainReject.CLOCK_SKEW)
        val other = TestNode.fresh("other-nik")
        defect(W08Kind.LEAF_SIGNED_BY_OTHER_NIK, { HostileCerts.leafSignedByOtherKey(it, other, rnd, now) }, ChainReject.BAD_SIGNATURE)
    }

    // ------------------------------------------------------------------------------------------- statuses

    @Test
    fun revokedAndSuspendedPins() {
        serverCase(W08Kind.REVOKED_PIN, "dialler's registry says REVOKED", { _, b -> HostileSpec(HostileCerts.honest(b.node)) }, chainRejected(ChainReject.PIN_REVOKED), tweak = { a, b -> a.registry.revoke(b.pin) })
        clientCase(W08Kind.REVOKED_PIN, "listener's registry says REVOKED", { a, _ -> HostileSpec(HostileCerts.honest(a.node)) }, chainRejected(ChainReject.PIN_REVOKED), tweak = { a, b -> b.registry.revoke(a.pin) })
        serverCase(W08Kind.SUSPENDED_PIN, "dialler's registry says SUSPENDED", { _, b -> HostileSpec(HostileCerts.honest(b.node)) }, chainRejected(ChainReject.PIN_SUSPENDED), tweak = { a, b -> a.registry.suspend(b.pin) })
        clientCase(W08Kind.SUSPENDED_PIN, "listener's registry says SUSPENDED", { a, _ -> HostileSpec(HostileCerts.honest(a.node)) }, chainRejected(ChainReject.PIN_SUSPENDED), tweak = { a, b -> b.registry.suspend(a.pin) })
    }

    // ------------------------------------------------------------------------------------------- protocol

    @Test
    fun alpnAndVersionFaults() {
        serverCase(W08Kind.WRONG_ALPN, "server speaks h2 only", { _, b -> HostileSpec(HostileCerts.honest(b.node), alpn = arrayOf("h2")) }, Expected(MeshTlsRefusal.PEER_ALERT, alert = TlsAlert.NO_APPLICATION_PROTOCOL))
        clientCase(W08Kind.WRONG_ALPN, "client offers h2 only", { a, _ -> HostileSpec(HostileCerts.honest(a.node), alpn = arrayOf("h2")) }, Expected(MeshTlsRefusal.ALPN_MISMATCH))
        serverCase(W08Kind.NO_ALPN, "server selects no ALPN", { _, b -> HostileSpec(HostileCerts.honest(b.node), alpn = null) }, Expected(MeshTlsRefusal.ALPN_MISSING))
        clientCase(W08Kind.NO_ALPN, "client offers no ALPN", { a, _ -> HostileSpec(HostileCerts.honest(a.node), alpn = null) }, Expected(MeshTlsRefusal.ALPN_MISSING))
        serverCase(W08Kind.TLS12_ONLY, "server only speaks TLS 1.2", { _, b -> HostileSpec(HostileCerts.honest(b.node), protocols = arrayOf("TLSv1.2")) }, Expected(MeshTlsRefusal.PEER_ALERT, alert = TlsAlert.PROTOCOL_VERSION))
        clientCase(W08Kind.TLS12_ONLY, "client only speaks TLS 1.2", { a, _ -> HostileSpec(HostileCerts.honest(a.node), protocols = arrayOf("TLSv1.2")) }, Expected(MeshTlsRefusal.PROTOCOL_VERSION))
    }

    // ------------------------------------------------------------------------------------------- resumption and early data

    private fun establishedServerSession(d: Duel, who: String) {
        assertTrue(d.honestEstablished, "$who: expected an established session")
        val conn = (d.honest.end as End.Ok).value
        W08.establishedSessions.incrementAndGet()
        val proof = conn.facts.peerChainLength == 2 && d.honest.observer.trustInvocations == 1 && d.honest.observer.verdict is ChainVerdict.Accepted
        if (proof) {
            W08.sessionsWithClientCertVerify.incrementAndGet()
            laws.bump("client-cert-verify")
        }
    }

    @Test
    fun resumptionAttempts() {
        // hostile client: connects, keeps the ticket, comes back with the same context and offers it
        val (a, b) = HonestNode.pairedPair()
        val client = HostileContext(HostileCerts.honest(a.node))
        val first = Scenarios.hostileClient(b, HostileSpec(HostileCerts.honest(a.node), peerHost = "127.0.0.1", context = client))
        val second = Scenarios.hostileClient(b, HostileSpec(HostileCerts.honest(a.node), peerHost = "127.0.0.1", context = client))
        establishedServerSession(first, "first connection")
        establishedServerSession(second, "second connection")
        val offered = second.hostileTap.clientHellos.single()
        assertTrue(offered.hasPsk, "the hostile client really offered a PSK")
        assertFalse(second.hostileTap.serverHellos.single().hasPsk, "the listener did not resume it")
        W08.hostilePskClientHellos.addAndGet(1)
        W08.cases.incrementAndGet()
        W08.exercised += "${W08Kind.RESUMPTION_ATTEMPT}/${W08Role.HOSTILE_CLIENT}"
        laws.bump("record-tap-detects-psk")
        W08.lines += "W08-case | jdk ${Matrix.jdk} | RESUMPTION_ATTEMPT | HOSTILE_CLIENT | ClientHello with pre_shared_key | not resumed: full handshake, verifier invoked ${second.honest.observer.trustInvocations} time, ServerHello without pre_shared_key"

        // hostile server that issues tickets: the honest dialler never offers one, however often it comes back
        val ticketServer = HostileContext(HostileCerts.honest(b.node))
        val book = SessionIdBook()
        repeat(3) {
            val d = Scenarios.hostileServer(a, ChainMode.ExpectPaired(b.pin), HostileSpec(HostileCerts.honest(b.node), context = ticketServer), book)
            assertTrue(d.honestEstablished)
            W08.honestClientHellos.addAndGet(d.honest.tap.clientHellos.size)
            W08.honestPskClientHellos.addAndGet(d.honest.tap.pskClientHellos)
            assertEquals(0, d.honest.tap.sessionIdReuses)
        }
        W08.cases.incrementAndGet()
        W08.exercised += "${W08Kind.RESUMPTION_ATTEMPT}/${W08Role.HOSTILE_SERVER}"
        W08.lines += "W08-case | jdk ${Matrix.jdk} | RESUMPTION_ATTEMPT | HOSTILE_SERVER | ticket-issuing server dialled 3 times | no pre_shared_key in any honest ClientHello"
    }

    @Test
    fun earlyDataAttempts() {
        val b = HonestNode.pairedPair().second
        val hello = RawHello(earlyData = true, pskIdentity = ByteArray(16) { (it * 3).toByte() }, seed = 31).record()
        val d = Scenarios.rawHello(b, hello, byteArrayOf(23, 3, 3, 0, 40) + ByteArray(40) { 1 })
        assertFalse(d.honestEstablished, "early data cannot establish anything")
        assertTrue(d.hostileTap.serverHellos.isNotEmpty() && !d.hostileTap.serverHellos.first().hasPsk, "no PSK selected, so no early data accepted")
        W08.cases.incrementAndGet()
        W08.exercised += "${W08Kind.EARLY_DATA_ATTEMPT}/${W08Role.HOSTILE_CLIENT}"
        W08.hostileEarlyDataClientHellos.addAndGet(d.hostileTap.earlyDataClientHellos)
        W08.hostilePskClientHellos.addAndGet(d.hostileTap.pskClientHellos)
        W08.refusals.merge(d.refusal.refusal.name, 1, Int::plus)
        laws.bump("typed-refusal")
        laws.bump("record-tap-detects-early-data", d.hostileTap.earlyDataClientHellos)
        W08.lines += "W08-case | jdk ${Matrix.jdk} | EARLY_DATA_ATTEMPT | HOSTILE_CLIENT | ClientHello with early_data and a bogus PSK, then an early record | refused ${d.refusal.refusal}; ServerHello without pre_shared_key"

        val (a2, b2) = HonestNode.pairedPair()
        val s = Scenarios.hostileServer(a2, ChainMode.ExpectPaired(b2.pin), HostileSpec(HostileCerts.honest(b2.node)))
        assertTrue(s.honestEstablished)
        W08.honestClientHellos.addAndGet(s.honest.tap.clientHellos.size)
        W08.honestPskClientHellos.addAndGet(s.honest.tap.pskClientHellos)
        assertEquals(0, s.honest.tap.earlyDataClientHellos, "an honest dialler never sends early_data")
        W08.cases.incrementAndGet()
        W08.exercised += "${W08Kind.EARLY_DATA_ATTEMPT}/${W08Role.HOSTILE_SERVER}"
        W08.lines += "W08-case | jdk ${Matrix.jdk} | EARLY_DATA_ATTEMPT | HOSTILE_SERVER | honest dialler against a ticket-issuing server | ClientHello without early_data"
    }

    // ------------------------------------------------------------------------------------------- controls: honest sessions with a client CertificateVerify

    @Test
    fun honestSessionsEstablishAndEachHasAVerifiedClientCertificate() {
        repeat(3) { i ->
            val (a, b) = HonestNode.pairedPair()
            val d = Scenarios.hostileClient(b, HostileSpec(HostileCerts.honest(a.node)))
            establishedServerSession(d, "control $i")
        }
        val (cc, sc) = Loop.pair()
        val (a, b) = HonestNode.pairedPair()
        val (c, s) = both({ a.dialPaired(cc, b.pin) }, { b.accept(sc) })
        val server = s.value
        assertTrue(server.end is End.Ok, "honest to honest")
        W08.establishedSessions.incrementAndGet()
        val conn = (server.end as End.Ok).value
        if (conn.facts.peerChainLength == 2 && server.observer.trustInvocations == 1 && server.observer.verdict is ChainVerdict.Accepted) {
            W08.sessionsWithClientCertVerify.incrementAndGet()
            laws.bump("client-cert-verify")
        }
        W08.honestClientHellos.addAndGet(c.value.tap.clientHellos.size)
        W08.honestPskClientHellos.addAndGet(c.value.tap.pskClientHellos)
        runCatching { (server.end as End.Ok).value.close() }
        runCatching { (c.value.end as End.Ok).value.close() }
        W08.lines += "W08-case | jdk ${Matrix.jdk} | CONTROL | BOTH | honest to honest and valid hostile-framed clients | established, client chain verified by verifyPeerChain"
    }
}
