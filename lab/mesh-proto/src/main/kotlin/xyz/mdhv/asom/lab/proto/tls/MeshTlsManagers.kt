package xyz.mdhv.asom.lab.proto.tls

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import xyz.mdhv.asom.lab.proto.trust.ChainMode
import xyz.mdhv.asom.lab.proto.trust.ChainVerdict
import xyz.mdhv.asom.lab.proto.trust.PeerChainVerifier
import xyz.mdhv.asom.lab.proto.trust.PinStatusSource

/** The node certificate, the session leaf certificate (both DER, from `CertTemplates`) and the leaf private key: what a node presents as `[leaf, node]`. */
class MeshIdentity(val nodeCertDer: ByteArray, val leafCertDer: ByteArray, val leafKey: PrivateKey)

/** Everything the verifier needs that is not the chain: the registry, a clock, the pairing window, and the production-keys switch (ERR-PT-14). */
class MeshTlsEnv(
    val registry: PinStatusSource,
    val clock: () -> Instant = { Instant.now() },
    val pairingWindowOpen: () -> Boolean = { false },
    val productionKeys: Boolean = true,
)

/**
 * What the managers of ONE connection decided, kept so the transport can name the refusal without parsing JSSE exception text (JDK 17 and 21
 * word those differently). One observer per connection, shared by that connection's key manager and trust manager.
 */
class HandshakeObserver {
    @Volatile
    var verdict: ChainVerdict? = null
        private set

    private val invocations = AtomicInteger()

    @Volatile
    var gate: MeshTlsRefusal? = null
        private set

    @Volatile
    var gateAlpn: String? = null
        private set

    /** True once the key manager was asked for a client certificate, i.e. the server sent a CertificateRequest. */
    @Volatile
    var certificateRequested: Boolean = false
        private set

    val trustInvocations: Int get() = invocations.get()

    internal fun recordVerdict(v: ChainVerdict) {
        invocations.incrementAndGet()
        verdict = v
    }

    internal fun recordCertificateRequested() {
        certificateRequested = true
    }

    internal fun recordGate(r: MeshTlsRefusal, alpn: String) {
        if (gate == null) {
            gate = r
            gateAlpn = alpn
        }
    }
}

/**
 * Trust evaluation for the mesh. It replaces platform PKIX entirely: no trust store, no hostname check, no endpoint-identification algorithm.
 * It calls ONLY [PeerChainVerifier] (verifyPeerChain, trust.md 3.2) and turns its verdict into an exception without text: a refusal is a
 * `CertificateException` with no message, which the JSSE maps to the single alert `certificate_unknown` for every cause.
 *
 * [expect] decides the client side (the dialer knows who it expects: a paired pin, or the pin from a QR); the server side has no expectation and
 * lets the verifier choose ESTABLISHED_SERVER or PAIRING_SERVER.
 */
class MeshTrustManager(
    private val expect: ChainMode?,
    private val env: MeshTlsEnv,
    private val observer: HandshakeObserver,
) : X509ExtendedTrustManager() {
    private fun der(chain: Array<X509Certificate>?): List<ByteArray> = try {
        chain.orEmpty().map { it.encoded }
    } catch (e: CertificateException) {
        emptyList()
    }

    private fun decide(v: ChainVerdict) {
        observer.recordVerdict(v)
        if (v !is ChainVerdict.Accepted) throw CertificateException()
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?, engine: SSLEngine?) {
        decide(PeerChainVerifier.verifyServer(der(chain), env.clock(), env.registry, env.pairingWindowOpen, env.productionKeys))
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?, engine: SSLEngine?) {
        val mode = expect ?: throw CertificateException()
        decide(PeerChainVerifier.verify(der(chain), mode, env.clock(), env.registry, env.pairingWindowOpen, env.productionKeys))
    }

    // The mesh transport is engine-only: a socket or a bare call has no engine to tie a verdict to, so it can never be trusted.
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException()

    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException()

    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = throw CertificateException()

    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = throw CertificateException()

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Presents `[leaf, node]` for the SSLEngine and nothing else. Before it presents anything it requires the negotiated ALPN to be `asom-mesh/1`:
 * the server therefore reveals no certificate to a dialler that did not offer it, and the client reveals none to a server that did not select it
 * (T13, T2). It offers only an EC key (the leaf is P-256; an RSA or EdDSA request gets nothing).
 */
class MeshKeyManager(identity: MeshIdentity, private val observer: HandshakeObserver) : X509ExtendedKeyManager() {
    private val chain: Array<X509Certificate>
    private val key: PrivateKey = identity.leafKey

    init {
        val cf = CertificateFactory.getInstance("X.509")
        chain = arrayOf(
            cf.generateCertificate(identity.leafCertDer.inputStream()) as X509Certificate,
            cf.generateCertificate(identity.nodeCertDer.inputStream()) as X509Certificate,
        )
    }

    private fun alpnOk(engine: SSLEngine?): Boolean {
        val alpn = engine?.handshakeApplicationProtocol.orEmpty()
        if (alpn == MeshTlsProfile.ALPN) return true
        observer.recordGate(if (alpn.isEmpty()) MeshTlsRefusal.ALPN_MISSING else MeshTlsRefusal.ALPN_MISMATCH, alpn)
        return false
    }

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<Principal>?, engine: SSLEngine?): String? =
        if (keyType == EC && alpnOk(engine)) ALIAS else null

    override fun chooseEngineClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, engine: SSLEngine?): String? {
        observer.recordCertificateRequested()
        return if (keyType.orEmpty().contains(EC) && alpnOk(engine)) ALIAS else null
    }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == ALIAS) chain.copyOf() else null

    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) key else null

    override fun getClientAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null

    override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?): String? = null

    override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null

    override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?): String? = null

    private companion object {
        const val ALIAS = "asom-mesh-leaf"
        const val EC = "EC"
    }
}
