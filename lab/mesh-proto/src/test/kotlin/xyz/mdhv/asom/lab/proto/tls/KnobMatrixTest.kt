package xyz.mdhv.asom.lab.proto.tls

import java.security.cert.CertificateException
import java.util.Collections
import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Timeout
import xyz.mdhv.asom.lab.proto.trust.ChainMode
import xyz.mdhv.asom.lab.proto.trust.ChainReject
import xyz.mdhv.asom.lab.proto.trust.ChainVerdict
import xyz.mdhv.asom.lab.proto.trust.LawCounters

/** The `S-A9` evidence: one line per knob and per JDK, printed from what the tests below observed. The doc `lab/mesh-proto/docs/S-A9-S-A11.md` is made from these lines. */
object Matrix {
    private val rows = Collections.synchronizedList(ArrayList<String>())
    val jdk: Int = Runtime.version().feature()

    fun record(knob: String, api: String, observed: String, evidence: String) {
        rows += "S-A9 | jdk $jdk | $knob | $api | $observed | $evidence"
    }

    fun print() = synchronized(rows) { rows.sorted().forEach { println(it) } }
}

/**
 * S-A9: for every TLS knob of design T2 and trust.md 3.2, a handshake that tries to violate it, on whichever JDK runs the suite (CI runs 17 and 21), and
 * where JSSE cannot enforce a knob scoped, the next layer that does. A violation must end in a typed refusal, never in an established session.
 * Evidence label: LAB, oracle: self, NOT DEVICE EVIDENCE.
 */
@Timeout(300)
class KnobMatrixTest {
    companion object {
        val laws = LawCounters("tls-profile")
        val SCOPED = MeshTlsProfile.signatureSchemesScoped

        @JvmStatic
        @AfterAll
        fun done() {
            Matrix.print()
            laws.finish(
                setOf(
                    "tls-1.3-only", "client-auth-required", "sigalg-only", "no-resumption", "no-early-data", "alpn-required", "no-sni", "no-hostname-check",
                    "chain-only-through-verifyPeerChain", "cipher-suites-tls13", "post-handshake-auth-absent", "key-type-ec-only", "jsse-default-controls",
                ) + (if (SCOPED) setOf("sigalg-offer-scoped") else emptySet()),
            )
        }

        private val TLS13_SUITES = setOf(0x1301, 0x1302, 0x1303)
        private fun hex(l: List<Int>) = l.joinToString(",") { "0x%04x".format(it) }
    }

    private val rnd = SplittableRandom(99)
    private val now get() = TestNode.NOW.epochSecond

    private fun pair(): Pair<HonestNode, HonestNode> = HonestNode.pairedPair()

    // ------------------------------------------------------------------------------------------- protocol versions

    @Test
    fun protocolVersionsOnly13() {
        val (a, b) = pair()
        val tls12Client = Scenarios.hostileClient(b, HostileSpec(HostileCerts.honest(a.node), protocols = arrayOf("TLSv1.2")))
        assertFalse(tls12Client.honestEstablished)
        assertEquals(MeshTlsRefusal.PROTOCOL_VERSION, tls12Client.refusal.refusal)
        assertEquals(TlsAlert.PROTOCOL_VERSION, Hostile.alertOf(tls12Client.hostile.handshakeError), "the TLS 1.2 client is told protocol_version")
        assertEquals(listOf(0x0303), tls12Client.hostileTap.clientHellos.single().supportedVersions)
        laws.bump("tls-1.3-only")

        val tls12Server = Scenarios.hostileServer(a, ChainMode.ExpectPaired(b.pin), HostileSpec(HostileCerts.honest(b.node), protocols = arrayOf("TLSv1.2")))
        assertFalse(tls12Server.honestEstablished)
        assertEquals(MeshTlsRefusal.PEER_ALERT, tls12Server.refusal.refusal)
        assertEquals(TlsAlert.PROTOCOL_VERSION, tls12Server.refusal.peerAlert)
        val honestHello = tls12Server.honest.tap.clientHellos.single()
        assertEquals(listOf(0x0304), honestHello.supportedVersions, "the honest dialer offers TLS 1.3 and nothing else")
        laws.bump("tls-1.3-only")

        val control = Scenarios.hostileClient(b, HostileSpec(HostileCerts.honest(a.node), protocols = arrayOf("TLSv1.3", "TLSv1.2")))
        assertTrue(control.honestEstablished, "control: a valid client that also offers 1.2 still negotiates 1.3")
        assertEquals(listOf(0x0304, 0x0303), control.hostileTap.clientHellos.single().supportedVersions, "control: without the scoped setting JSSE offers TLS 1.2 too")
        laws.bump("jsse-default-controls")
        Matrix.record(
            "protocol versions (TLS 1.3 only)", "SSLEngine.setEnabledProtocols / SSLParameters.setProtocols (engine)",
            "yes: a TLS 1.2-only client is refused ${tls12Client.refusal.refusal}, its alert was ${Hostile.alertOf(tls12Client.hostile.handshakeError)}; a TLS 1.2-only server is refused ${tls12Server.refusal.refusal}/${tls12Server.refusal.peerAlert}",
            "honest ClientHello supported_versions [${hex(honestHello.supportedVersions)}]; control default offers [${hex(control.hostileTap.clientHellos.single().supportedVersions)}]; post-handshake assert session.protocol",
        )
    }

    // ------------------------------------------------------------------------------------------- client authentication

    @Test
    fun clientAuthenticationIsRequired() {
        val (a, b) = pair()
        val noCert = Scenarios.hostileClient(b, HostileSpec(HostileCerts.nothing()))
        assertFalse(noCert.honestEstablished)
        assertEquals(MeshTlsRefusal.PEER_CERTIFICATE_MISSING, noCert.refusal.refusal)
        val alert = Hostile.alertOf(noCert.hostile.handshakeError) ?: noCert.hostile.alertAfterHandshake
        assertTrue(alert == TlsAlert.BAD_CERTIFICATE || alert == TlsAlert.CERTIFICATE_REQUIRED, "got $alert")
        assertEquals(0, noCert.honest.observer.trustInvocations, "no certificate, so the verifier was never asked")
        laws.bump("client-auth-required")

        val lax = Scenarios.hostileServer(a, ChainMode.ExpectPaired(b.pin), HostileSpec(HostileCerts.honest(b.node), needClientAuth = false))
        assertFalse(lax.honestEstablished, "a server that never asks for our certificate is refused by the dialler")
        assertEquals(MeshTlsRefusal.CLIENT_AUTH_NOT_REQUESTED, lax.refusal.refusal)
        laws.bump("client-auth-required")

        val plainServer = HostileContext(HostileCerts.honest(b.node))
        val plainClient = HostileContext(HostileCerts.nothing())
        val (cc, sc) = Loop.pair()
        val (s, c) = both(
            { Hostile.run(SocketNet(sc), false, HostileSpec(HostileCerts.honest(b.node), needClientAuth = false, context = plainServer), afterHandshake = { it.write(byteArrayOf(1), 0, 1) }) },
            { Hostile.run(SocketNet(cc), true, HostileSpec(HostileCerts.nothing(), context = plainClient), afterHandshake = { it.write(byteArrayOf(1), 0, 1) }) },
        )
        assertTrue(s.value.established && c.value.established, "control: stock JSSE does not require a client certificate")
        laws.bump("jsse-default-controls")
        Matrix.record(
            "client authentication required", "SSLParameters.setNeedClientAuth(true) (engine)",
            "yes: a client with no certificate is refused ${noCert.refusal.refusal}, alert $alert; a server that does not ask is refused ${lax.refusal.refusal} by the dialler",
            "control: a stock server without needClientAuth established with a certificate-less client; verifier invocations on the refused handshake: ${noCert.honest.observer.trustInvocations}",
        )
    }

    // ------------------------------------------------------------------------------------------- signature schemes, curves, key types

    private data class SigCase(val name: String, val chain: (HonestNode) -> HostileChain, val serverReject: Set<ChainReject>)

    @Test
    fun signatureSchemesAndKeysAreEnforcedAtTheNextLayerWhenJsseCannot() {
        val (a, b) = pair()
        val cases = listOf(
            SigCase("RSA key (RSA-PSS CertificateVerify)", { HostileCerts.rsaLeaf(it.node, rnd, now) }, setOf(ChainReject.KEY_UNSUPPORTED)),
            SigCase("P-384 key", { HostileCerts.p384Leaf(it.node, rnd, now) }, setOf(ChainReject.KEY_UNSUPPORTED)),
            SigCase("SHA-1 signed certificate", { HostileCerts.sha1Leaf(it.node, rnd, now) }, setOf(ChainReject.SIG_ALG_UNSUPPORTED)),
        )
        val observed = ArrayList<String>()
        var honestHello: HelloInfo? = null
        for (c in cases) {
            val srv = Scenarios.hostileServer(a, ChainMode.ExpectPaired(b.pin), HostileSpec(c.chain(b)))
            assertFalse(srv.honestEstablished, "${c.name}: the dialler must refuse")
            honestHello = srv.honest.tap.clientHellos.single()
            val r = srv.refusal
            if (r.refusal == MeshTlsRefusal.PEER_CHAIN_REJECTED) {
                assertContains(c.serverReject, r.chainReject, "${c.name}: verifyPeerChain's own code")
                laws.bump("chain-only-through-verifyPeerChain")
            } else {
                assertEquals(MeshTlsRefusal.PEER_ALERT, r.refusal, "${c.name}: either the verifier refuses or the peer cannot even sign")
                assertEquals(TlsAlert.HANDSHAKE_FAILURE, r.peerAlert)
                assertTrue(SCOPED, "${c.name}: only a JSSE that restricts the signature schemes can make the peer fail first")
            }
            laws.bump("sigalg-only")
            observed += "${c.name}: ${r.refusal}${r.chainReject?.let { "/$it" } ?: ""}${r.peerAlert?.let { "/$it" } ?: ""}"

            val cli = Scenarios.hostileClient(b, HostileSpec(c.chain(a)))
            assertFalse(cli.honestEstablished, "${c.name}: the listener must refuse")
            val cr = cli.refusal
            assertTrue(
                cr.refusal == MeshTlsRefusal.PEER_CHAIN_REJECTED || cr.refusal == MeshTlsRefusal.PEER_CERTIFICATE_MISSING || cr.refusal == MeshTlsRefusal.HANDSHAKE_FAILED,
                "${c.name} as client: got ${cr.refusal}",
            )
            if (cr.refusal == MeshTlsRefusal.PEER_CHAIN_REJECTED) assertContains(c.serverReject, cr.chainReject)
            laws.bump("sigalg-only")
            observed += "${c.name} as client: ${cr.refusal}${cr.chainReject?.let { "/$it" } ?: ""}"
        }
        val hello = honestHello!!
        if (SCOPED) {
            assertEquals(listOf(0x0403), hello.signatureAlgorithms, "scoped to ecdsa_secp256r1_sha256 on this JDK")
            laws.bump("sigalg-offer-scoped")
        } else {
            assertContains(hello.signatureAlgorithms, 0x0403)
            assertTrue(hello.signatureAlgorithms.size > 1, "JDK 17 offers its default list: no scoped API")
        }
        Matrix.record(
            "signature schemes (ecdsa_secp256r1_sha256 only)",
            if (SCOPED) "SSLParameters.setSignatureSchemes (reflection; JDK 19+), plus key manager (EC only) and verifyPeerChain (P-256, ecdsa-with-SHA256)" else "no scoped API on this JDK (only the jdk.tls.*.SignatureSchemes system property, forbidden by C12); key manager (EC only) and verifyPeerChain (P-256, ecdsa-with-SHA256)",
            if (SCOPED) "partly by JSSE (offered list restricted), fully by the next layer" else "no by JSSE; yes at the next layer (key manager, verifier)",
            "honest ClientHello signature_algorithms [${hex(hello.signatureAlgorithms)}]; violators as server: ${observed.filter { !it.contains("as client") }.joinToString("; ")}; as client: ${observed.filter { it.contains("as client") }.joinToString("; ")}",
        )
    }

    @Test
    fun theKeyManagerOffersOnlyAnEcKey() {
        val a = HonestNode(TestNode.testOnly("key1"))
        val km = MeshKeyManager(a.node.identity(), HandshakeObserver())
        val engine = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, null, null) }.createSSLEngine()
        assertNull(km.chooseEngineServerAlias("RSA", null, engine))
        assertNull(km.chooseEngineServerAlias("EdDSA", null, engine))
        assertNull(km.chooseEngineServerAlias("RSASSA-PSS", null, engine))
        assertNull(km.chooseEngineClientAlias(arrayOf("RSA", "EdDSA", "RSASSA-PSS"), null, engine))
        assertNull(km.chooseEngineServerAlias("EC", null, engine), "with no ALPN negotiated even the EC key is not offered")
        assertNull(km.getCertificateChain("anything-else"))
        assertNull(km.getPrivateKey("anything-else"))
        assertNull(km.chooseServerAlias("EC", null, null as java.net.Socket?), "the socket variants offer nothing")
        laws.bump("key-type-ec-only")
        Matrix.record(
            "key type presented", "X509ExtendedKeyManager.chooseEngine*Alias (context)", "yes: EC only, and only once ALPN asom-mesh/1 is negotiated",
            "unit test of MeshKeyManager with RSA, EdDSA, RSASSA-PSS, and with no ALPN",
        )
    }

    // ------------------------------------------------------------------------------------------- groups and cipher suites

    @Test
    fun namedGroupsAndCipherSuitesAreThePlatformDefaultsOfTls13() {
        val (a, b) = pair()
        val srv = Scenarios.hostileServer(a, ChainMode.ExpectPaired(b.pin), HostileSpec(HostileCerts.honest(b.node)))
        assertTrue(srv.honestEstablished, "control: honest to hostile-but-valid server")
        val hello = srv.honest.tap.clientHellos.single()
        assertTrue(TLS13_SUITES.containsAll(hello.cipherSuites) && hello.cipherSuites.isNotEmpty(), "only TLS 1.3 suites are listed: ${hex(hello.cipherSuites)}")
        assertTrue(hello.supportedGroups.isNotEmpty())
        laws.bump("cipher-suites-tls13")
        val legacy = Scenarios.hostileClient(b, HostileSpec(HostileCerts.honest(a.node), protocols = arrayOf("TLSv1.3", "TLSv1.2")))
        val legacySuites = legacy.hostileTap.clientHellos.single().cipherSuites
        assertTrue(legacySuites.any { it !in TLS13_SUITES }, "control: the JSSE default also lists TLS 1.2 suites")
        laws.bump("jsse-default-controls")
        val setGroups = runCatching { javax.net.ssl.SSLParameters::class.java.getMethod("setNamedGroups", Array<String>::class.java) }.isSuccess
        Matrix.record(
            "cipher suites", "none set: the TLS 1.3 protocol restriction removes TLS 1.2 suites (engine)", "yes by protocol restriction; suites are the platform's",
            "honest ClientHello cipher_suites [${hex(hello.cipherSuites)}]; control default hello also lists ${legacySuites.count { it !in TLS13_SUITES }} TLS 1.2 suites",
        )
        Matrix.record(
            "named groups / curves", "SSLParameters.setNamedGroups ${if (setGroups) "exists (JDK 20+) but is NOT used: trust.md 3.2 says platform defaults" else "does not exist on this JDK (added in 20)"}",
            "not enforced by us (platform defaults); certificate key curve is enforced by verifyPeerChain (P-256 only)",
            "honest ClientHello supported_groups [${hex(hello.supportedGroups)}]",
        )
    }

    // ------------------------------------------------------------------------------------------- ALPN

    @Test
    fun alpnIsRequiredOnBothSidesAndNeverFallsBack() {
        val (a, b) = pair()
        // the dialler offers asom-mesh/1; the hostile server only speaks h2
        val h2Server = Scenarios.hostileServer(a, ChainMode.ExpectPaired(b.pin), HostileSpec(HostileCerts.honest(b.node), alpn = arrayOf("h2")))
        assertFalse(h2Server.honestEstablished)
        assertEquals(MeshTlsRefusal.PEER_ALERT, h2Server.refusal.refusal)
        assertEquals(TlsAlert.NO_APPLICATION_PROTOCOL, h2Server.refusal.peerAlert)
        laws.bump("alpn-required")

        // the hostile server ignores ALPN altogether: the dialler never presents its certificate
        val silentServer = Scenarios.hostileServer(a, ChainMode.ExpectPaired(b.pin), HostileSpec(HostileCerts.honest(b.node), alpn = null))
        assertFalse(silentServer.honestEstablished, "a server that selects no ALPN is refused: no silent fallback")
        assertEquals(MeshTlsRefusal.ALPN_MISSING, silentServer.refusal.refusal)
        assertEquals(0, silentServer.hostile.trustCalls, "the dialler sent no certificate to a server that did not select the ALPN")
        laws.bump("alpn-required")

        // the hostile client offers another ALPN
        val h2Client = Scenarios.hostileClient(b, HostileSpec(HostileCerts.honest(a.node), alpn = arrayOf("h2")))
        assertFalse(h2Client.honestEstablished)
        assertEquals(MeshTlsRefusal.ALPN_MISMATCH, h2Client.refusal.refusal)
        assertEquals(TlsAlert.NO_APPLICATION_PROTOCOL, Hostile.alertOf(h2Client.hostile.handshakeError))
        laws.bump("alpn-required")

        // the hostile client offers no ALPN at all: JSSE would accept it; the listener's key manager does not present a certificate
        val noAlpn = Scenarios.hostileClient(b, HostileSpec(HostileCerts.honest(a.node), alpn = null))
        assertFalse(noAlpn.honestEstablished)
        assertEquals(MeshTlsRefusal.ALPN_MISSING, noAlpn.refusal.refusal)
        assertTrue(noAlpn.hostileTap.bytesRead < 400, "the listener sent only a ServerHello and an alert, never a certificate: ${noAlpn.hostileTap.bytesRead} bytes")
        laws.bump("alpn-required")

        val control = HostileContext(HostileCerts.honest(b.node))
        val (cc, sc) = Loop.pair()
        val (s, c) = both(
            { Hostile.run(SocketNet(sc), false, HostileSpec(HostileCerts.honest(b.node), context = control), afterHandshake = { it.write(byteArrayOf(1), 0, 1) }) },
            { Hostile.run(SocketNet(cc), true, HostileSpec(HostileCerts.honest(a.node), alpn = null), afterHandshake = { it.write(byteArrayOf(1), 0, 1) }) },
        )
        assertTrue(s.value.established && c.value.established)
        assertEquals("", s.value.alpn.orEmpty(), "control: stock JSSE completes a handshake with no ALPN at all when the client offers none")
        laws.bump("jsse-default-controls")

        val full = Scenarios.hostileClient(b, HostileSpec(HostileCerts.honest(a.node)))
        assertTrue(full.honestEstablished)
        Matrix.record(
            "ALPN asom-mesh/1 required", "SSLParameters.setApplicationProtocols (engine) + key manager gate + post-handshake getApplicationProtocol check",
            "partly by JSSE: a non-matching ALPN is refused with no_application_protocol (${h2Server.refusal.peerAlert} seen by the dialler, ${h2Client.refusal.refusal} by the listener); an ABSENT ALPN is accepted by JSSE and refused by the next layer (${noAlpn.refusal.refusal}, ${silentServer.refusal.refusal})",
            "listener bytes sent to a client with no ALPN: ${noAlpn.hostileTap.bytesRead} (an honest handshake: ${full.hostileTap.bytesRead})",
        )
    }

    // ------------------------------------------------------------------------------------------- resumption and tickets

    @Test
    fun resumptionIsNeverOfferedAndNeverAccepted() {
        val (a, b) = pair()

        // control 1: stock JSSE with a shared server context DOES resume, and the trust manager is not asked about the client again
        val sharedServer = HostileContext(HostileCerts.honest(b.node))
        val sharedClient = HostileContext(HostileCerts.honest(a.node))
        var helloTaps = ArrayList<RecordTap>()
        repeat(2) {
            val (cc, sc) = Loop.pair()
            val clientTap = RecordTap(SocketNet(cc))
            helloTaps += clientTap
            val (s, c) = both(
                { Hostile.run(SocketNet(sc), false, HostileSpec(HostileCerts.honest(b.node), context = sharedServer), afterHandshake = { io -> io.write(byteArrayOf(1), 0, 1) }) },
                { Hostile.run(clientTap, true, HostileSpec(HostileCerts.honest(a.node), peerHost = "127.0.0.1", context = sharedClient), afterHandshake = { io -> io.write(byteArrayOf(1), 0, 1) }) },
            )
            assertTrue(s.value.established && c.value.established)
        }
        val firstHello = helloTaps[0].clientHellos.single()
        val secondHello = helloTaps[1].clientHellos.single()
        val secondServerHello = helloTaps[1].serverHellos.single()
        assertFalse(firstHello.hasPsk)
        assertTrue(secondHello.hasPsk, "control: a client that reuses its context offers a PSK")
        assertTrue(secondServerHello.hasPsk, "control: a stock server resumes (pre_shared_key in the ServerHello)")
        assertEquals(1, sharedServer.trustManager.calls.get(), "control: the resumed session never asked the trust manager about the client (no CertificateVerify)")
        laws.bump("jsse-default-controls")

        // the mesh listener (a fresh SSLContext per accepted connection) refuses to resume what a client brings
        val client = HostileContext(HostileCerts.honest(a.node))
        val rounds = ArrayList<Duel>()
        repeat(2) { rounds += Scenarios.hostileClient(b, HostileSpec(HostileCerts.honest(a.node), peerHost = "127.0.0.1", context = client)) }
        assertTrue(rounds.all { it.honestEstablished }, "the hostile client has a valid chain, so both sessions are established")
        val offered = rounds[1].hostileTap.clientHellos.single()
        assertTrue(offered.hasPsk, "the second connection DID offer a PSK (the listener issued a ticket that the client kept)")
        assertFalse(rounds[1].hostileTap.serverHellos.single().hasPsk, "the mesh listener did not resume")
        val second = rounds[1].honest
        assertEquals(1, second.observer.trustInvocations, "a full handshake: the verifier was asked about the client")
        assertTrue(second.observer.verdict is ChainVerdict.Accepted)
        assertEquals(2, client.trustManager.calls.get(), "and the client verified the server again in round 2 (a full handshake, not a resumption)")
        laws.bump("no-resumption")

        // the honest dialler never offers one, even when the same server is dialled again and again
        val ticketServer = HostileContext(HostileCerts.honest(b.node))
        val book = SessionIdBook()
        var psk = 0
        repeat(3) {
            val d = Scenarios.hostileServer(a, ChainMode.ExpectPaired(b.pin), HostileSpec(HostileCerts.honest(b.node), context = ticketServer), book)
            assertTrue(d.honestEstablished)
            psk += d.honest.tap.pskClientHellos
        }
        assertEquals(0, psk)
        laws.bump("no-resumption", 3)
        Matrix.record(
            "session resumption / tickets / cache",
            "client: a fresh SSLContext per dial (context) and no peer host on the engine; server: a fresh SSLContext per accepted connection (context); SSLSessionContext.setSessionTimeout/setSessionCacheSize and SSLSession.invalidate do not stop a shared server context from resuming (separate probe, see doc)",
            "client: yes (never offers). server: yes by a fresh context per connection; tickets are STILL issued (the client kept one) but cannot be redeemed",
            "control: a stock shared server context resumed (ServerHello pre_shared_key) and skipped the client trust check; mesh listener: PSK offered by the hostile client, not accepted, full handshake with verifier invoked ${second.observer.trustInvocations} time",
        )
    }

    private class Round(val offeredPsk: Boolean, val resumed: Boolean, val serverTrustCalls: Int)

    /** Two connections between plain JSSE peers that share their contexts; returns what the second one did. */
    private fun secondConnection(
        a: HonestNode, b: HonestNode, serverSetup: (HostileContext) -> Unit = {}, serverAfter: (TlsEngineIo) -> Unit = {}, clientAfter: (TlsEngineIo) -> Unit = {},
    ): Round {
        val serverCtx = HostileContext(HostileCerts.honest(b.node))
        val clientCtx = HostileContext(HostileCerts.honest(a.node))
        serverSetup(serverCtx)
        var tap: RecordTap? = null
        repeat(2) {
            val (cc, sc) = Loop.pair()
            val t = RecordTap(SocketNet(cc))
            tap = t
            val (s, c) = both(
                { Hostile.run(SocketNet(sc), false, HostileSpec(HostileCerts.honest(b.node), context = serverCtx), afterHandshake = { io -> io.write(byteArrayOf(1), 0, 1) }, afterRead = serverAfter) },
                { Hostile.run(t, true, HostileSpec(HostileCerts.honest(a.node), peerHost = "127.0.0.1", context = clientCtx), afterHandshake = { io -> io.write(byteArrayOf(1), 0, 1) }, afterRead = clientAfter) },
            )
            assertTrue(s.value.established && c.value.established)
        }
        return Round(tap!!.clientHellos.single().hasPsk, tap!!.serverHellos.single().hasPsk, serverCtx.trustManager.calls.get())
    }

    @Test
    fun sessionContextSettingsDoNotSuppressTicketsOrResumptionOnASharedContext() {
        val (a, b) = pair()
        val stock = secondConnection(a, b)
        val tuned = secondConnection(a, b, serverSetup = { it.ssl.serverSessionContext.sessionTimeout = 1; it.ssl.serverSessionContext.sessionCacheSize = 1 })
        val serverInvalidates = secondConnection(a, b, serverAfter = { it.engine.session.invalidate() })
        val clientInvalidates = secondConnection(a, b, clientAfter = { it.engine.session.invalidate() })
        assertTrue(stock.offeredPsk && stock.resumed && stock.serverTrustCalls == 1, "stock: the client kept a ticket and the server resumed it")
        assertTrue(tuned.offeredPsk && tuned.resumed, "setSessionTimeout(1) and setSessionCacheSize(1) on the server context do not stop resumption")
        assertTrue(serverInvalidates.offeredPsk && serverInvalidates.resumed, "invalidating the server's session does not stop it either: the ticket is self-contained")
        assertFalse(clientInvalidates.offeredPsk, "invalidating the CLIENT's session after the handshake makes the client stop offering it")
        laws.bump("jsse-default-controls", 4)
        Matrix.record(
            "server tickets: can JSSE suppress them per context?", "SSLSessionContext.setSessionTimeout / setSessionCacheSize / SSLSession.invalidate on the server (context, engine)",
            "no: on a shared server context the second connection offered a PSK and was resumed in every variant (stock, timeout 1 + cache 1, server-side invalidate); the trust manager was not asked about the client again",
            "client-side SSLSession.invalidate() after the handshake does stop the client offering (offered=${clientInvalidates.offeredPsk}); the mesh avoids server resumption with a fresh SSLContext per accepted connection instead",
        )
    }

    // ------------------------------------------------------------------------------------------- early data

    @Test
    fun earlyDataIsNeverAcceptedAndNeverSent() {
        val (a, b) = pair()
        val plain = RawHello(earlyData = true, pskIdentity = ByteArray(16) { it.toByte() }, seed = 5).record()
        val d = Scenarios.rawHello(b, plain)
        assertEquals(1, d.hostileTap.earlyDataClientHellos, "the tap sees the early_data extension")
        assertEquals(1, d.hostileTap.pskClientHellos, "the tap sees the pre_shared_key extension")
        assertTrue(d.hostileTap.serverHellos.isNotEmpty(), "the listener answered with a ServerHello")
        assertFalse(d.hostileTap.serverHellos.first().hasPsk, "no PSK was selected, so no early data can be accepted")
        assertFalse(d.honestEstablished, "the raw client cannot finish a handshake, so nothing is established")
        laws.bump("no-early-data")

        val follow = byteArrayOf(23, 3, 3, 0, 40) + ByteArray(40) { 7 }
        val d2 = Scenarios.rawHello(b, RawHello(earlyData = true, pskIdentity = ByteArray(16) { 9 }, seed = 6).record(), follow)
        assertFalse(d2.honestEstablished, "early application data does not establish anything")
        laws.bump("no-early-data")

        val dial = Scenarios.hostileServer(a, ChainMode.ExpectPaired(b.pin), HostileSpec(HostileCerts.honest(b.node)))
        assertEquals(0, dial.honest.tap.earlyDataClientHellos)
        laws.bump("no-early-data")
        Matrix.record(
            "0-RTT early data", "none: JSSE (17 and 21) implements no early data at all; ClientHello never carries early_data",
            "yes (by absence): a raw ClientHello with early_data and a bogus PSK got a ServerHello with no pre_shared_key and no session (${d.refusal.refusal}); a following early record gave ${d2.refusal.refusal}",
            "honest ClientHello extensions [${dial.honest.tap.clientHellos.single().extensionTypes.joinToString(",")}] (no 42)",
        )
    }

    // ------------------------------------------------------------------------------------------- SNI, hostname, post-handshake auth

    @Test
    fun noSniNoHostnameCheckNoPostHandshakeAuth() {
        val (a, b) = pair()
        val dial = Scenarios.hostileServer(a, ChainMode.ExpectPaired(b.pin), HostileSpec(HostileCerts.honest(b.node)))
        assertTrue(dial.honestEstablished)
        val hello = dial.honest.tap.clientHellos.single()
        assertFalse(hello.hasServerName, "the honest ClientHello carries no server_name")
        assertEquals(emptyList(), hello.serverNames)
        laws.bump("no-sni")
        assertFalse(49 in hello.extensionTypes, "no post_handshake_auth: a server cannot ask for a certificate after the handshake")
        laws.bump("post-handshake-auth-absent")

        val withSni = Scenarios.hostileClient(b, HostileSpec(HostileCerts.honest(a.node), peerHost = "peer.example", serverNames = listOf("peer.example")))
        assertTrue(withSni.hostileTap.clientHellos.single().hasServerName, "control: a client that names a host sends SNI")
        assertTrue(withSni.honestEstablished, "the listener ignores an SNI it did not ask for (gating is S-A11, not enabled)")
        laws.bump("jsse-default-controls")

        val idCheck = Scenarios.hostileClient(b, HostileSpec(HostileCerts.honest(a.node), endpointIdentification = "HTTPS", peerHost = "127.0.0.1"))
        assertTrue(idCheck.honestEstablished, "certificates carry no names; no hostname is ever compared")
        laws.bump("no-hostname-check")
        val conn = (dial.honest.end as End.Ok).value
        assertNull(conn.facts.endpointIdentification)
        assertEquals(emptyList(), conn.facts.serverNames)
        laws.bump("no-hostname-check")
        Matrix.record(
            "SNI", "SSLParameters.setServerNames(empty) and an engine created without a peer host (engine)", "yes: no server_name on either JDK",
            "honest ClientHello extensions [${hello.extensionTypes.joinToString(",")}]; control with a dotted host name sends ext 0; the listener ignores an SNI it receives",
        )
        Matrix.record(
            "hostname verification", "SSLParameters.endpointIdentificationAlgorithm = null; custom X509ExtendedTrustManager (context)",
            "yes: not performed. JSSE runs no identity check of its own for a custom trust manager, even with the algorithm set to HTTPS",
            "a hostile client that set HTTPS identification against 127.0.0.1 and a nameless certificate was established with the listener",
        )
        Matrix.record(
            "certificate request context / post-handshake authentication", "none: SSLEngine (17, 21) has no post-handshake client authentication",
            "yes (by absence): the ClientHello has no post_handshake_auth (49); only the in-handshake CertificateRequest with an empty context exists",
            "UNVERIFIED against a hostile post-handshake CertificateRequest (no generator for one)",
        )
    }

    // ------------------------------------------------------------------------------------------- trust manager

    @Test
    fun theTrustManagerItselfRefusesEveryHostileChainWhateverTheJsseDoes() {
        val (a, b) = pair()
        val x509 = { der: ByteArray -> java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as java.security.cert.X509Certificate }
        val cases = listOf(
            HostileCerts.p384Leaf(b.node, rnd, now) to ChainReject.KEY_UNSUPPORTED,
            HostileCerts.rsaLeaf(b.node, rnd, now) to ChainReject.KEY_UNSUPPORTED,
            HostileCerts.sha1Leaf(b.node, rnd, now) to ChainReject.SIG_ALG_UNSUPPORTED,
            HostileCerts.caLeaf(b.node, rnd, now) to ChainReject.LEAF_IS_CA,
            HostileCerts.expiredLeaf(b.node, rnd, now) to ChainReject.CLOCK_SKEW,
            HostileCerts.leafOnly(b.node) to ChainReject.CHAIN_LENGTH,
        )
        for ((hostile, code) in cases) {
            val obs = HandshakeObserver()
            val tm = MeshTrustManager(ChainMode.ExpectPaired(b.pin), a.env(), obs)
            val chain = hostile.chain!!.map(x509).toTypedArray()
            assertFailsWith<CertificateException>(hostile.name) { tm.checkServerTrusted(chain, "EC", null as javax.net.ssl.SSLEngine?) }
            assertEquals(code, (obs.verdict as ChainVerdict.Rejected).code, hostile.name)
            laws.bump("chain-only-through-verifyPeerChain")
        }
        val obs = HandshakeObserver()
        val ok = HostileCerts.honest(b.node).chain!!.map(x509).toTypedArray()
        MeshTrustManager(ChainMode.ExpectPaired(b.pin), a.env(), obs).checkServerTrusted(ok, "EC", null as javax.net.ssl.SSLEngine?)
        assertTrue(obs.verdict is ChainVerdict.Accepted, "control: the honest chain passes the same entry point")
    }

    @Test
    fun theTrustManagerIsEngineOnlyAndEmptyOfIssuers() {
        val a = HonestNode(TestNode.testOnly("key1"))
        val obs = HandshakeObserver()
        val tm = MeshTrustManager(null, a.env(), obs)
        val chain = arrayOf<java.security.cert.X509Certificate>()
        assertFailsWith<CertificateException> { tm.checkClientTrusted(chain, "EC") }
        assertFailsWith<CertificateException> { tm.checkServerTrusted(chain, "EC") }
        assertFailsWith<CertificateException> { tm.checkClientTrusted(chain, "EC", null as java.net.Socket?) }
        assertFailsWith<CertificateException> { tm.checkServerTrusted(chain, "EC", null as java.net.Socket?) }
        assertFailsWith<CertificateException> { tm.checkServerTrusted(chain, "EC", null as javax.net.ssl.SSLEngine?) }
        assertEquals(0, tm.acceptedIssuers.size)
        assertEquals(0, obs.trustInvocations, "none of the engine-less variants reached the verifier")
        assertFailsWith<CertificateException> { tm.checkClientTrusted(chain, "EC", null as javax.net.ssl.SSLEngine?) }
        val rejected = obs.verdict as ChainVerdict.Rejected
        assertEquals(ChainReject.CHAIN_LENGTH, rejected.code, "an empty chain reaches verifyPeerChain and is refused there")
        assertNotNull(rejected.alert)
        laws.bump("chain-only-through-verifyPeerChain")
        Matrix.record(
            "trust evaluation", "X509ExtendedTrustManager calling only PeerChainVerifier (context)", "yes: no PKIX, no system trust store, engine variants only",
            "unit test: socket and engine-less variants always refuse; the empty chain is refused by the verifier with ${rejected.code}",
        )
    }
}
