package xyz.mdhv.asom.lab.proto.integration

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.SplittableRandom
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.ledger.MeshKind
import xyz.mdhv.asom.lab.ledger.Phase
import xyz.mdhv.asom.lab.proto.session.DialReport
import xyz.mdhv.asom.lab.proto.tls.Hostile
import xyz.mdhv.asom.lab.proto.tls.HostileCerts
import xyz.mdhv.asom.lab.proto.tls.HostileChain
import xyz.mdhv.asom.lab.proto.tls.HostileContext
import xyz.mdhv.asom.lab.proto.tls.HostileResult
import xyz.mdhv.asom.lab.proto.tls.HostileSpec
import xyz.mdhv.asom.lab.proto.tls.Loop
import xyz.mdhv.asom.lab.proto.tls.Matrix
import xyz.mdhv.asom.lab.proto.tls.MeshTlsException
import xyz.mdhv.asom.lab.proto.tls.MeshTlsRefusal
import xyz.mdhv.asom.lab.proto.tls.RawHello
import xyz.mdhv.asom.lab.proto.tls.RecordTap
import xyz.mdhv.asom.lab.proto.tls.SessionIdBook
import xyz.mdhv.asom.lab.proto.tls.SocketNet
import xyz.mdhv.asom.lab.proto.tls.TestNode
import xyz.mdhv.asom.lab.proto.tls.TlsAlert
import xyz.mdhv.asom.lab.proto.tls.W08Kind
import xyz.mdhv.asom.lab.proto.tls.W08Role
import xyz.mdhv.asom.lab.proto.trust.ChainReject

/**
 * W08, the handshake half, against the REAL session engine (LAB_SPEC 7.4): a hostile node in both roles over loopback TLS, with the honest end a full node (real
 * registry, real JSONL ledger, `MeshNode.dial` and the listener glue) and not just the TLS transport. Expected, and checked here: zero accepted bad chains, no
 * `pre_shared_key` in any ClientHello of the honest dialler, a verified client chain in every established session, every refusal typed, and for each refusal the
 * rows of LAB_SPEC 7.6: a refused dial is a `DIAL` intent and an outcome with the typed code and nothing else; a refused inbound connection is never a session and is
 * counted in the one `INBOUND_REFUSED` row. The refusal types allowed per case are the ones of the TLS track (`W08HandshakeTest`, ERRATA ERR-PL-4, ERR-PL-5, ERR-PL-7).
 */
object HostileHandshakeSession {
    private val windows = System.getProperty("os.name").lowercase().startsWith("windows")
    private val rnd = SplittableRandom(808)
    private val now get() = TestNode.NOW.epochSecond
    val lines: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    private class Expected(val refusal: MeshTlsRefusal, val chain: ChainReject? = null, val alert: TlsAlert? = null) {
        fun matches(e: MeshTlsException) = e.refusal == refusal && (chain == null || e.chainReject == chain) && (alert == null || e.peerAlert == alert)
        override fun toString() = "$refusal${chain?.let { "/$it" } ?: ""}${alert?.let { "/$it" } ?: ""}"
    }

    /** The DIAL outcome of LAB_SPEC 7.6 for each typed refusal, written out by hand (it is not read back from `DialOutcomes`). */
    private fun expectedDialCode(r: MeshTlsRefusal): String = when (r) {
        MeshTlsRefusal.PEER_CHAIN_REJECTED, MeshTlsRefusal.PEER_CERTIFICATE_MISSING -> "pin-mismatch"
        MeshTlsRefusal.ALPN_MISSING, MeshTlsRefusal.ALPN_MISMATCH, MeshTlsRefusal.PROTOCOL_VERSION, MeshTlsRefusal.CLIENT_AUTH_NOT_REQUESTED,
        MeshTlsRefusal.HANDSHAKE_FAILED, MeshTlsRefusal.PEER_CLOSED,
        -> "not-tls"
        MeshTlsRefusal.HANDSHAKE_TIMEOUT -> "timeout"
        MeshTlsRefusal.PEER_ALERT, MeshTlsRefusal.TRANSPORT_IO -> "refused"
    }

    private fun chainRejected(code: ChainReject) = Expected(MeshTlsRefusal.PEER_CHAIN_REJECTED, chain = code)

    // ------------------------------------------------------------------------------------------------------------ the two duels

    private class ServerDuel(val w: TlsWorld, val report: DialReport, val dialer: TlsDialer, val hostile: HostileResult, val hostileTap: RecordTap)

    /** The hostile node is the TLS server; the honest end is A, dialling B's pin through `MeshNode.dial`. */
    private fun hostileServer(w: TlsWorld, spec: HostileSpec): ServerDuel {
        Loopback().use { lb ->
            var hostile: HostileResult? = null
            var hostileTap: RecordTap? = null
            val t = Thread {
                val ch = lb.accept()
                hostileTap = RecordTap(SocketNet(ch))
                hostile = Hostile.run(hostileTap!!, client = false, spec = spec)
            }.also { it.isDaemon = true; it.name = "hostile-server"; it.start() }
            val dialer = TlsDialer(w.a, w.b.pin, w.log, "A>X")
            val report = w.a.node.dial(w.b.pin, lb.address, "qr", dialer)
            if (report.connection != null) {
                // the dial connected; the hostile node is waiting for the first byte. A real session says HELLO, so open one
                val s = w.a.node.openDialed(report, w.b.pin)
                w.track(SessionDriver(s, { w.clockA.peek() }, "A-hs").start())
            }
            t.join(30_000)
            return ServerDuel(w, report, dialer, hostile!!, hostileTap!!)
        }
    }

    private class ClientDuel(val w: TlsWorld, val accepted: Accepted, val hostile: HostileResult, val hostileTap: RecordTap)

    /** The hostile node is the TLS client of the honest listener B, which runs the real session engine on whatever it accepts. */
    private fun hostileClient(
        w: TlsWorld, spec: HostileSpec, windowOpen: Boolean = false, readAfter: Boolean = true, expectEstablished: Boolean = false, afterHandshake: (xyz.mdhv.asom.lab.proto.tls.TlsEngineIo) -> Unit = {},
    ): ClientDuel {
        if (windowOpen) w.b.pairingWindow = true
        val lb = Loopback()
        var accepted: Accepted? = null
        val t = Thread {
            try {
                accepted = acceptOn(w.b, lb.accept(), "B<X", w.log)
                if (accepted!!.session != null && !expectEstablished) accepted!!.driver?.close()
            } finally {
                lb.close()
            }
        }.also { it.isDaemon = true; it.name = "honest-listener"; it.start() }
        val ch = SocketChannel.open(InetSocketAddress(Loop.ADDRESS, lb.port))
        ch.socket().tcpNoDelay = true
        val tap = RecordTap(SocketNet(ch))
        val result = Hostile.run(tap, client = true, spec = spec, afterHandshake = afterHandshake, readAfter = readAfter)
        t.join(30_000)
        val acc = accepted!!
        acc.driver?.let { w.track(it) }
        return ClientDuel(w, acc, result, tap)
    }

    // ------------------------------------------------------------------------------------------------------------ what a refusal must look like

    private fun judgeAlert(kind: W08Kind, role: W08Role, label: String, e: MeshTlsException, hostile: HostileResult) {
        if (e.refusal != MeshTlsRefusal.PEER_CHAIN_REJECTED) return
        val seen = Hostile.alertOf(hostile.handshakeError) ?: hostile.alertAfterHandshake
        if (seen != null) {
            assertEquals(TlsAlert.CERTIFICATE_UNKNOWN, seen, "$kind/$role $label: every chain refusal is the same alert")
        } else {
            val ownFailure = hostile.handshakeError is javax.net.ssl.SSLException
            assertTrue(
                ownFailure || windows,
                "$kind/$role $label: the refused peer saw no alert, and its own engine did not fail first (a reset is tolerated on Windows only): established=${hostile.established} hsErr=${hostile.handshakeError} readErr=${hostile.readError}",
            )
        }
        if (role == W08Role.HOSTILE_SERVER) assertEquals(0, hostile.trustCalls, "$kind/$role $label: the dialler refused the server before it sent its own certificate")
    }

    private fun serverCase(
        kind: W08Kind, label: String, spec: (TlsWorld) -> HostileSpec, vararg allowed: Expected, tweak: (TlsWorld) -> Unit = {},
    ) {
        W08Tls.handshakeCases.incrementAndGet()
        W08Tls.exercised += "$kind/${W08Role.HOSTILE_SERVER}"
        TlsWorld(900L + W08Tls.handshakeCases.get()).use { w ->
            tweak(w)
            val d = hostileServer(w, spec(w))
            if (d.report.connection != null) {
                W08Tls.acceptedBadChains.incrementAndGet()
                lines += "W08-case | jdk ${Matrix.jdk} | $kind | HOSTILE_SERVER | $label | ACCEPTED (BAD)"
                error("$kind/HOSTILE_SERVER $label: the honest dialler established a session with a hostile node")
            }
            val e = d.dialer.failure as? MeshTlsException ?: error("$kind/HOSTILE_SERVER $label: the dial failed with ${d.dialer.failure} (an untyped refusal)")
            assertTrue(allowed.any { it.matches(e) }, "$kind/HOSTILE_SERVER $label: got ${e.refusal}/${e.chainReject}/${e.peerAlert}, allowed ${allowed.joinToString(" or ")}")
            judgeAlert(kind, W08Role.HOSTILE_SERVER, label, e, d.hostile)
            assertTrue(d.dialer.tap!!.clientHellos.isNotEmpty(), "$kind/HOSTILE_SERVER $label: the tap saw no ClientHello of the honest dialler")
            // the rows of LAB_SPEC 7.6 for a dial that did not connect: a DIAL intent and an outcome with the typed code, no session, no frame
            val code = expectedDialCode(e.refusal)
            assertEquals(code, d.report.code, "$kind/HOSTILE_SERVER $label: the DIAL outcome code")
            val rows = w.a.rows()
            assertEquals(listOf(MeshKind.DIAL to Phase.INTENT, MeshKind.DIAL to Phase.OUTCOME), rows.map { it.meshKind to it.phase }, "$kind/HOSTILE_SERVER $label: the rows of a refused dial")
            assertEquals(code, rows[1].meshCode)
            assertEquals(599, rows[1].status)
            assertTrue(rows[1].overheadBytes == null, "a dial that did not connect has no handshake figure")
            assertTrue(w.a.node.openSessions().isEmpty(), "$kind/HOSTILE_SERVER $label: a refused dial left a session")
            W08Tls.dialOutcomes.merge(code, 1, Int::plus)
            W08Tls.rowChecks.addAndGet(4)
            lines += "W08-case | jdk ${Matrix.jdk} | $kind | HOSTILE_SERVER | $label | refused ${e.refusal}${e.chainReject?.let { "/$it" } ?: ""}${e.peerAlert?.let { "/$it" } ?: ""} -> DIAL $code"
        }
    }

    private fun clientCase(
        kind: W08Kind, label: String, spec: (TlsWorld) -> HostileSpec, vararg allowed: Expected, tweak: (TlsWorld) -> Unit = {},
    ) {
        W08Tls.handshakeCases.incrementAndGet()
        W08Tls.exercised += "$kind/${W08Role.HOSTILE_CLIENT}"
        TlsWorld(900L + W08Tls.handshakeCases.get()).use { w ->
            tweak(w)
            val d = hostileClient(w, spec(w))
            W08Tls.hostilePskClientHellos.addAndGet(d.hostileTap.pskClientHellos)
            W08Tls.hostileEarlyDataClientHellos.addAndGet(d.hostileTap.earlyDataClientHellos)
            if (d.accepted.session != null) {
                W08Tls.acceptedBadChains.incrementAndGet()
                lines += "W08-case | jdk ${Matrix.jdk} | $kind | HOSTILE_CLIENT | $label | ACCEPTED (BAD)"
                error("$kind/HOSTILE_CLIENT $label: the honest listener established a session with a hostile node")
            }
            val e = d.accepted.refusal ?: error("$kind/HOSTILE_CLIENT $label: neither a session nor a typed refusal")
            assertTrue(allowed.any { it.matches(e) }, "$kind/HOSTILE_CLIENT $label: got ${e.refusal}/${e.chainReject}/${e.peerAlert}, allowed ${allowed.joinToString(" or ")}")
            judgeAlert(kind, W08Role.HOSTILE_CLIENT, label, e, d.hostile)
            assertTrue(w.b.node.openSessions().isEmpty(), "$kind/HOSTILE_CLIENT $label: a refused inbound connection became a session")
            assertEquals(emptyList(), w.b.rows(), "$kind/HOSTILE_CLIENT $label: nothing is written for an unauthenticated connection until the 10-minute row")
            val row = w.b.node.inboundRefused.flush(force = true)
            assertNotNull(row)
            assertEquals("refused:1", row.meshCode)
            assertEquals(1, w.b.rows().size)
            W08Tls.inboundRefusedRows.incrementAndGet()
            W08Tls.rowChecks.addAndGet(3)
            lines += "W08-case | jdk ${Matrix.jdk} | $kind | HOSTILE_CLIENT | $label | refused ${e.refusal}${e.chainReject?.let { "/$it" } ?: ""}${e.peerAlert?.let { "/$it" } ?: ""} -> INBOUND_REFUSED"
        }
    }

    private fun defect(kind: W08Kind, build: (TestNode) -> HostileChain, code: ChainReject, serverAlt: List<Expected> = emptyList(), clientAlt: List<Expected> = emptyList()) {
        serverCase(kind, "hostile server chain", { w -> HostileSpec(build(w.b.tn)) }, chainRejected(code), *serverAlt.toTypedArray())
        clientCase(kind, "hostile client chain", { w -> HostileSpec(build(w.a.tn)) }, chainRejected(code), *clientAlt.toTypedArray())
    }

    // ------------------------------------------------------------------------------------------------------------ the cases

    fun chains() {
        val stranger = TestNode.fresh("stranger")
        serverCase(W08Kind.WRONG_CHAIN, "valid chain, other pin", { _ -> HostileSpec(HostileCerts.unpaired(stranger)) }, chainRejected(ChainReject.PIN_MISMATCH))
        clientCase(W08Kind.WRONG_CHAIN, "valid chain, unknown pin", { _ -> HostileSpec(HostileCerts.unpaired(stranger)) }, chainRejected(ChainReject.PIN_UNKNOWN))
        serverCase(W08Kind.WRONG_CHAIN, "leaf of one node under another node cert", { w -> HostileSpec(HostileCerts.mismatchedPair(w.b.tn, stranger)) }, chainRejected(ChainReject.ISSUER_MISMATCH))
        clientCase(W08Kind.WRONG_CHAIN, "leaf of one node under another node cert", { w -> HostileSpec(HostileCerts.mismatchedPair(w.a.tn, stranger)) }, chainRejected(ChainReject.ISSUER_MISMATCH))
        serverCase(W08Kind.WRONG_CHAIN, "leaf only", { w -> HostileSpec(HostileCerts.leafOnly(w.b.tn)) }, chainRejected(ChainReject.CHAIN_LENGTH))
        clientCase(W08Kind.WRONG_CHAIN, "leaf only", { w -> HostileSpec(HostileCerts.leafOnly(w.a.tn)) }, chainRejected(ChainReject.CHAIN_LENGTH))
        serverCase(
            W08Kind.MISSING_CHAIN, "server with nothing to present", { _ -> HostileSpec(HostileCerts.nothing()) },
            Expected(MeshTlsRefusal.PEER_ALERT, alert = TlsAlert.HANDSHAKE_FAILURE), Expected(MeshTlsRefusal.PEER_CLOSED),
        )
        clientCase(W08Kind.MISSING_CHAIN, "client with nothing to present", { _ -> HostileSpec(HostileCerts.nothing()) }, Expected(MeshTlsRefusal.PEER_CERTIFICATE_MISSING))
        clientCase(W08Kind.NO_CLIENT_CERT, "client with no key manager at all", { _ -> HostileSpec(HostileCerts.nothing(), noKeyManager = true) }, Expected(MeshTlsRefusal.PEER_CERTIFICATE_MISSING))
    }

    fun certificateFaults() {
        defect(W08Kind.CA_LEAF, { HostileCerts.caLeaf(it, rnd, now) }, ChainReject.LEAF_IS_CA)
        defect(W08Kind.P384_KEY, { HostileCerts.p384Leaf(it, rnd, now) }, ChainReject.KEY_UNSUPPORTED)
        defect(
            W08Kind.RSA_KEY, { HostileCerts.rsaLeaf(it, rnd, now) }, ChainReject.KEY_UNSUPPORTED,
            serverAlt = listOf(Expected(MeshTlsRefusal.PEER_ALERT, alert = TlsAlert.HANDSHAKE_FAILURE)), clientAlt = listOf(Expected(MeshTlsRefusal.PEER_CERTIFICATE_MISSING)),
        )
        defect(W08Kind.SHA1_SIGNATURE, { HostileCerts.sha1Leaf(it, rnd, now) }, ChainReject.SIG_ALG_UNSUPPORTED)
        defect(W08Kind.EXPIRED_LEAF, { HostileCerts.expiredLeaf(it, rnd, now) }, ChainReject.CLOCK_SKEW)
        val other = TestNode.fresh("other-nik")
        defect(W08Kind.LEAF_SIGNED_BY_OTHER_NIK, { HostileCerts.leafSignedByOtherKey(it, other, rnd, now) }, ChainReject.BAD_SIGNATURE)
    }

    fun statuses() {
        serverCase(W08Kind.REVOKED_PIN, "dialler's registry says REVOKED", { w -> HostileSpec(HostileCerts.honest(w.b.tn)) }, chainRejected(ChainReject.PIN_REVOKED), tweak = { w -> w.a.registry.revoke(w.b.pin, 3) })
        clientCase(W08Kind.REVOKED_PIN, "listener's registry says REVOKED", { w -> HostileSpec(HostileCerts.honest(w.a.tn)) }, chainRejected(ChainReject.PIN_REVOKED), tweak = { w -> w.b.registry.revoke(w.a.pin, 3) })
        serverCase(W08Kind.SUSPENDED_PIN, "dialler's registry says SUSPENDED", { w -> HostileSpec(HostileCerts.honest(w.b.tn)) }, chainRejected(ChainReject.PIN_SUSPENDED), tweak = { w -> w.a.registry.pause(w.b.pin, 2) })
        clientCase(W08Kind.SUSPENDED_PIN, "listener's registry says SUSPENDED", { w -> HostileSpec(HostileCerts.honest(w.a.tn)) }, chainRejected(ChainReject.PIN_SUSPENDED), tweak = { w -> w.b.registry.pause(w.a.pin, 2) })
    }

    fun protocol() {
        serverCase(W08Kind.WRONG_ALPN, "server speaks h2 only", { w -> HostileSpec(HostileCerts.honest(w.b.tn), alpn = arrayOf("h2")) }, Expected(MeshTlsRefusal.PEER_ALERT, alert = TlsAlert.NO_APPLICATION_PROTOCOL))
        clientCase(W08Kind.WRONG_ALPN, "client offers h2 only", { w -> HostileSpec(HostileCerts.honest(w.a.tn), alpn = arrayOf("h2")) }, Expected(MeshTlsRefusal.ALPN_MISMATCH))
        serverCase(W08Kind.NO_ALPN, "server selects no ALPN", { w -> HostileSpec(HostileCerts.honest(w.b.tn), alpn = null) }, Expected(MeshTlsRefusal.ALPN_MISSING))
        clientCase(W08Kind.NO_ALPN, "client offers no ALPN", { w -> HostileSpec(HostileCerts.honest(w.a.tn), alpn = null) }, Expected(MeshTlsRefusal.ALPN_MISSING))
        serverCase(W08Kind.TLS12_ONLY, "server only speaks TLS 1.2", { w -> HostileSpec(HostileCerts.honest(w.b.tn), protocols = arrayOf("TLSv1.2")) }, Expected(MeshTlsRefusal.PEER_ALERT, alert = TlsAlert.PROTOCOL_VERSION))
        clientCase(W08Kind.TLS12_ONLY, "client only speaks TLS 1.2", { w -> HostileSpec(HostileCerts.honest(w.a.tn), protocols = arrayOf("TLSv1.2")) }, Expected(MeshTlsRefusal.PROTOCOL_VERSION))
    }

    /** A hostile client that keeps its ticket and offers it again, and a ticket-issuing hostile server dialled three times by the honest node. */
    fun resumption() {
        W08Tls.handshakeCases.incrementAndGet()
        TlsWorld(950).use { w ->
            val client = HostileContext(HostileCerts.honest(w.a.tn))
            // after the handshake the hostile client says a valid HELLO and reads the answer: reading is what makes the engine take the listener's NewSessionTicket
            val hello = xyz.mdhv.asom.lab.proto.session.Build.hello(w.a.pin.nodeId)
            val sayHello = { io: xyz.mdhv.asom.lab.proto.tls.TlsEngineIo -> io.write(hello, 0, hello.size) }
            val first = hostileClient(w, HostileSpec(HostileCerts.honest(w.a.tn), peerHost = "127.0.0.1", context = client), expectEstablished = true, afterHandshake = sayHello)
            val second = hostileClient(w, HostileSpec(HostileCerts.honest(w.a.tn), peerHost = "127.0.0.1", context = client), expectEstablished = true, afterHandshake = sayHello)
            for ((n, d) in listOf("first connection" to first, "second connection" to second)) {
                assertNotNull(d.accepted.session, "$n: expected an established session")
                assertEquals(1, d.accepted.conn!!.observer.trustInvocations, "$n: the verifier ran exactly once, so this was a full handshake")
                W08Tls.sessionsUnderSessionEngine.incrementAndGet()
            }
            val offered = second.hostileTap.clientHellos.single()
            assertTrue(offered.hasPsk, "the hostile client really offered a PSK")
            assertFalse(second.hostileTap.serverHellos.single().hasPsk, "the listener did not resume it")
            W08Tls.hostilePskClientHellos.addAndGet(1)
            W08Tls.exercised += "${W08Kind.RESUMPTION_ATTEMPT}/${W08Role.HOSTILE_CLIENT}"
            lines += "W08-case | jdk ${Matrix.jdk} | RESUMPTION_ATTEMPT | HOSTILE_CLIENT | ClientHello with pre_shared_key against the session engine | not resumed: full handshake, verifier invoked once, ServerHello without pre_shared_key, session established"
        }
        W08Tls.handshakeCases.incrementAndGet()
        TlsWorld(951).use { w ->
            val ticketServer = HostileContext(HostileCerts.honest(w.b.tn))
            repeat(3) {
                val d = hostileServer(w, HostileSpec(HostileCerts.honest(w.b.tn), context = ticketServer))
                assertNotNull(d.report.connection, "the honest dialler established with the ticket-issuing server")
                assertEquals(0, d.dialer.tap!!.pskClientHellos)
                assertEquals(0, d.dialer.tap!!.sessionIdReuses)
                W08Tls.sessionsUnderSessionEngine.incrementAndGet()
            }
            W08Tls.exercised += "${W08Kind.RESUMPTION_ATTEMPT}/${W08Role.HOSTILE_SERVER}"
            lines += "W08-case | jdk ${Matrix.jdk} | RESUMPTION_ATTEMPT | HOSTILE_SERVER | ticket-issuing server dialled 3 times through MeshNode.dial | no pre_shared_key in any honest ClientHello"
        }
    }

    fun earlyData() {
        W08Tls.handshakeCases.incrementAndGet()
        TlsWorld(960).use { w ->
            val hello = RawHello(earlyData = true, pskIdentity = ByteArray(16) { (it * 3).toByte() }, seed = 31).record()
            val follow = byteArrayOf(23, 3, 3, 0, 40) + ByteArray(40) { 1 }
            val lb = Loopback()
            var accepted: Accepted? = null
            val t = Thread {
                try {
                    accepted = acceptOn(w.b, lb.accept(), "B<X", w.log)
                } finally {
                    lb.close()
                }
            }.also { it.isDaemon = true; it.start() }
            val ch = SocketChannel.open(InetSocketAddress(Loop.ADDRESS, lb.port))
            val tap = RecordTap(SocketNet(ch), SessionIdBook())
            tap.write(ByteBuffer.wrap(hello), 5000)
            tap.write(ByteBuffer.wrap(follow), 5000)
            val buf = ByteBuffer.allocate(8192)
            val deadline = System.nanoTime() + 1_500_000_000L
            while (tap.serverHellos.isEmpty() && System.nanoTime() < deadline) {
                buf.clear()
                val n = try {
                    tap.read(buf, 300)
                } catch (e: java.net.SocketTimeoutException) {
                    continue
                } catch (e: java.io.IOException) {
                    break
                }
                if (n < 0) break
            }
            tap.close()
            t.join(30_000)
            val acc = accepted!!
            assertTrue(acc.session == null && acc.refusal != null, "early data cannot establish anything")
            assertTrue(tap.serverHellos.isNotEmpty() && !tap.serverHellos.first().hasPsk, "no PSK selected, so no early data accepted")
            W08Tls.hostileEarlyDataClientHellos.addAndGet(tap.earlyDataClientHellos)
            W08Tls.hostilePskClientHellos.addAndGet(tap.pskClientHellos)
            assertTrue(w.b.node.openSessions().isEmpty())
            val row = w.b.node.inboundRefused.flush(force = true)
            assertEquals("refused:1", row!!.meshCode)
            W08Tls.inboundRefusedRows.incrementAndGet()
            W08Tls.exercised += "${W08Kind.EARLY_DATA_ATTEMPT}/${W08Role.HOSTILE_CLIENT}"
            lines += "W08-case | jdk ${Matrix.jdk} | EARLY_DATA_ATTEMPT | HOSTILE_CLIENT | ClientHello with early_data and a bogus PSK, then an early record | refused ${acc.refusal!!.refusal}; ServerHello without pre_shared_key; INBOUND_REFUSED"
        }
        W08Tls.handshakeCases.incrementAndGet()
        TlsWorld(961).use { w ->
            val d = hostileServer(w, HostileSpec(HostileCerts.honest(w.b.tn)))
            assertNotNull(d.report.connection)
            assertEquals(0, d.dialer.tap!!.earlyDataClientHellos, "an honest dialler never sends early_data")
            W08Tls.sessionsUnderSessionEngine.incrementAndGet()
            W08Tls.exercised += "${W08Kind.EARLY_DATA_ATTEMPT}/${W08Role.HOSTILE_SERVER}"
            lines += "W08-case | jdk ${Matrix.jdk} | EARLY_DATA_ATTEMPT | HOSTILE_SERVER | honest dialler against a ticket-issuing server | ClientHello without early_data"
        }
    }

    /** Honest sessions over the real engine, each with a verified client chain: the controls of the suite. */
    fun controls() {
        repeat(3) { i ->
            W08Tls.handshakeCases.incrementAndGet()
            TlsWorld(970L + i).use { w ->
                val d = hostileClient(w, HostileSpec(HostileCerts.honest(w.a.tn)), readAfter = false, expectEstablished = true)
                assertNotNull(d.accepted.session, "control $i: a valid hostile-framed client is admitted")
                W08Tls.sessionsUnderSessionEngine.incrementAndGet()
            }
        }
        repeat(3) { i ->
            W08Tls.handshakeCases.incrementAndGet()
            TlsWorld(980L + i).use { w ->
                val link = w.connect()
                w.awaitEstablished(link)
                assertTrue(link.sessionA!!.established && link.sessionB!!.established)
                W08Tls.sessionsUnderSessionEngine.addAndGet(2)
                link.sessionA!!.close()
                Wait.until("close") { link.sessionB!!.closed }
            }
        }
        W08Tls.exercised += "CONTROL/BOTH"
        lines += "W08-case | jdk ${Matrix.jdk} | CONTROL | BOTH | honest to honest and valid hostile-framed clients over the session engine | established, client chain verified by verifyPeerChain"
    }

    fun required(): Set<String> {
        val s = java.util.TreeSet<String>()
        for (k in W08Kind.entries) for (r in W08Role.entries) {
            if (k == W08Kind.NO_CLIENT_CERT && r == W08Role.HOSTILE_SERVER) continue
            s += "$k/$r"
        }
        return s
    }

    fun all() {
        chains()
        certificateFaults()
        statuses()
        protocol()
        resumption()
        earlyData()
        controls()
    }
}
