package xyz.mdhv.asom.lab.proto.tls

import java.net.Socket
import java.nio.ByteBuffer
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Timeout
import xyz.mdhv.asom.lab.proto.trust.LawCounters

/** The pairwise hourly SNI token of design T13: `trunc64(HMAC(pairKey, epochHour))`, as an RFC 4648 base32 label (13 characters). */
object SniToken {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    fun token(pairKey: ByteArray, epochHour: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pairKey, "HmacSHA256"))
        val digest = mac.doFinal(ByteBuffer.allocate(8).putLong(epochHour).array()).copyOf(8)
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in digest) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    /** SNI must be a host name (RFC 6066); the token is the first label of a name under `.invalid` (RFC 6761), so it is a well-formed name that resolves nowhere. */
    fun label(token: String): String = "$token.asom.invalid"
}

/**
 * S-A11: the listener presents its certificate only to a dialler whose SNI carries the expected token, decided in `X509ExtendedKeyManager`. This is the
 * smallest experiment; it is NOT part of the mesh profile (the default stays no SNI) and nothing in `MeshTls` calls it.
 */
class SniGatedKeyManager(private val inner: HostileKeyManager, private val accepts: (String?) -> Boolean) : X509ExtendedKeyManager() {
    val decisions = CopyOnWriteArrayList<String>()

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<Principal>?, engine: SSLEngine?): String? {
        val session = engine?.handshakeSession as? ExtendedSSLSession
        val name = (session?.requestedServerNames?.firstOrNull() as? SNIHostName)?.asciiName
        val ok = accepts(name)
        decisions += if (ok) "granted" else if (name == null) "denied: no server_name" else "denied: server_name is not the token"
        return if (ok) inner.chooseEngineServerAlias(keyType, issuers, engine) else null
    }

    override fun chooseEngineClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, engine: SSLEngine?): String? = inner.chooseEngineClientAlias(keyType, issuers, engine)
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = inner.getCertificateChain(alias)
    override fun getPrivateKey(alias: String?): PrivateKey? = inner.getPrivateKey(alias)
    override fun getClientAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?): String? = null
    override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?): String? = null
}

@Timeout(120)
class SniGatingSpikeTest {
    companion object {
        val laws = LawCounters("sni-gating-spike")
        private val lines: MutableList<String> = Collections.synchronizedList(ArrayList())

        @JvmStatic
        @AfterAll
        fun done() {
            lines.forEach { println(it) }
            laws.finish(setOf("token-granted", "no-sni-denied", "wrong-token-denied", "stale-token-denied", "replay-within-hour", "no-certificate-disclosed"))
        }
    }

    private class Run(val server: HostileResult, val client: HostileResult, val clientTap: RecordTap, val decisions: List<String>)

    private fun run(b: HonestNode, a: HonestNode, expectedLabel: String, clientNames: List<String>?): Run {
        val gate = SniGatedKeyManager(HostileKeyManager(HostileCerts.honest(b.node))) { it == expectedLabel }
        val serverCtx = HostileContext(HostileCerts.honest(b.node), keyManagerOverride = gate)
        val (cc, sc) = Loop.pair()
        val clientTap = RecordTap(SocketNet(cc))
        val (s, c) = both(
            { Hostile.run(SocketNet(sc), false, HostileSpec(HostileCerts.honest(b.node), context = serverCtx), afterHandshake = { it.write(byteArrayOf(1), 0, 1) }) },
            { Hostile.run(clientTap, true, HostileSpec(HostileCerts.honest(a.node), serverNames = clientNames), afterHandshake = { it.write(byteArrayOf(1), 0, 1) }) },
        )
        return Run(s.value, c.value, clientTap, gate.decisions.toList())
    }

    @Test
    fun theKeyManagerCanGateTheCertificateOnTheSniToken() {
        val (a, b) = HonestNode.pairedPair()
        val pairKey = ByteArray(32) { (it * 7 + 1).toByte() }
        val hour = TestNode.NOW.epochSecond / 3600
        val expected = SniToken.label(SniToken.token(pairKey, hour))
        val stale = SniToken.label(SniToken.token(pairKey, hour - 1))
        val other = SniToken.label(SniToken.token(ByteArray(32) { 9 }, hour))

        val granted = run(b, a, expected, listOf(expected))
        assertTrue(granted.server.established && granted.client.established, "the right token gets a normal handshake")
        assertEquals(listOf("granted"), granted.decisions)
        assertEquals(1, granted.client.trustCalls, "the dialler saw and checked the certificate")
        assertEquals(listOf(expected), granted.clientTap.clientHellos.single().serverNames, "the token travels in clear in the ClientHello")
        laws.bump("token-granted")

        val denied = linkedMapOf(
            "no SNI" to run(b, a, expected, null),
            "a token under another pair key" to run(b, a, expected, listOf(other)),
            "last hour's token" to run(b, a, expected, listOf(stale)),
        )
        for ((name, r) in denied) {
            assertFalse(r.server.established || r.client.established, "$name: no handshake")
            assertTrue(r.decisions.isNotEmpty() && r.decisions.all { it.startsWith("denied") }, "$name: ${r.decisions}")
            assertEquals(0, r.client.trustCalls, "$name: no certificate reached the dialler, so it learned no pin")
            assertTrue(r.clientTap.bytesRead < 400, "$name: the listener sent ${r.clientTap.bytesRead} bytes (a ServerHello and an alert), against ${granted.clientTap.bytesRead} for a granted handshake")
            laws.bump("no-certificate-disclosed")
        }
        laws.bump("no-sni-denied")
        laws.bump("wrong-token-denied")
        laws.bump("stale-token-denied")

        // a passive observer that copied the token from the wire can use it for the rest of the hour: stated in T13
        val replayed = granted.clientTap.clientHellos.single().serverNames.single()
        val replay = run(b, a, expected, listOf(replayed))
        assertTrue(replay.server.established && replay.client.established, "the replayed token is accepted")
        laws.bump("replay-within-hour")

        // a token that is not a dotted host name: set explicitly it IS sent (the dot rule only applies to a name derived from a peer host)
        val bare = run(b, a, expected, listOf("notadottedname"))
        val bareSent = bare.clientTap.clientHellos.single().serverNames
        lines += "S-A11 | jdk ${Matrix.jdk} | right token | listener decision ${granted.decisions} | dialler checked the certificate ${granted.client.trustCalls} time | listener bytes ${granted.clientTap.bytesRead}"
        denied.forEach { (n, r) -> lines += "S-A11 | jdk ${Matrix.jdk} | $n | listener decision ${r.decisions} | dialler checked the certificate ${r.client.trustCalls} times | listener bytes ${r.clientTap.bytesRead}, handshake failed, alert seen ${Hostile.alertOf(r.client.handshakeError) ?: Hostile.alertOf(r.client.readError)}" }
        lines += "S-A11 | jdk ${Matrix.jdk} | replayed token (copied from a ClientHello) | listener decision ${replay.decisions} | established ${replay.server.established}"
        lines += "S-A11 | jdk ${Matrix.jdk} | label without a dot | name sent on the wire ${bareSent} | listener decision ${bare.decisions} | established ${bare.server.established}"
        lines += "S-A11 | jdk ${Matrix.jdk} | verdict | works with caveats: JSSE gates the certificate in X509ExtendedKeyManager on JDK 17 and 21; the token is visible and replayable for the hour; Conscrypt and Network.framework are UNVERIFIED"
    }
}
