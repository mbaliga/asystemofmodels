package xyz.mdhv.asom.lab.proto.tls

import java.io.ByteArrayInputStream
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.SplittableRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import xyz.mdhv.asom.lab.manifest.Es256
import xyz.mdhv.asom.lab.manifest.Spki
import xyz.mdhv.asom.lab.proto.trust.CertTemplates
import xyz.mdhv.asom.lab.proto.trust.Der
import xyz.mdhv.asom.lab.proto.trust.Oids

/** A chain a hostile node presents, with the key it signs CertificateVerify with. [chain] null means it presents nothing. */
class HostileChain(val name: String, val chain: List<ByteArray>?, val key: PrivateKey?)

/**
 * The certificate faults of W08 (LAB_SPEC 7.4). Every fault is on top of an otherwise valid node, so the only reason `verifyPeerChain` can have for
 * refusing it is the fault itself (the node is paired in the honest side's registry in these cases).
 */
object HostileCerts {
    private fun name(prefix: String, nikSpki: ByteArray): ByteArray =
        Der.sequence(Der.set(Der.sequence(Der.oid(Oids.COMMON_NAME), Der.utf8String("$prefix ${Spki.nodeTag(nikSpki)}"))))

    private fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) Der.sequence(Der.oid(oid), Der.boolean(true), Der.octetString(value)) else Der.sequence(Der.oid(oid), Der.octetString(value))

    private fun tbs(
        serial: ByteArray, issuer: ByteArray, notBefore: Long, notAfter: Long, subject: ByteArray, spki: ByteArray, extensions: List<ByteArray>, sigAlgId: ByteArray,
    ): ByteArray = Der.sequence(
        Der.explicit(0, Der.integer(2)),
        Der.integerUnsigned(serial),
        sigAlgId,
        issuer,
        Der.sequence(Der.time(notBefore), Der.time(notAfter)),
        subject,
        spki,
        Der.explicit(3, Der.sequence(*extensions.toTypedArray())),
    )

    private fun leafExtensions(nik: ByteArray, ca: Boolean, keyUsage: ByteArray, keyUsageUnused: Int): List<ByteArray> = listOf(
        extension(Oids.BASIC_CONSTRAINTS, true, if (ca) Der.sequence(Der.boolean(true)) else Der.sequence()),
        extension(Oids.KEY_USAGE, true, Der.bitString(keyUsage, keyUsageUnused)),
        extension(Oids.EXT_KEY_USAGE, false, Der.sequence(Der.oid(Oids.SERVER_AUTH), Der.oid(Oids.CLIENT_AUTH))),
        extension(Oids.AUTHORITY_KEY_ID, false, Der.sequence(Der.implicitPrimitive(0, CertTemplates.subjectKeyId(nik)))),
    )

    private val DIGITAL_SIGNATURE = byteArrayOf(0x80.toByte())

    private fun signedByNik(node: TestNode, tbs: ByteArray): ByteArray = CertTemplates.assemble(tbs, Es256.sign(node.nik.private, tbs))

    /** A leaf that is itself a CA (basicConstraints CA:TRUE, keyCertSign). */
    fun caLeaf(node: TestNode, rnd: SplittableRandom, nowSec: Long): HostileChain {
        val leafKey = Es256.generate()
        val keyUsage = byteArrayOf(0x84.toByte())
        val t = tbs(
            TestNode.serial(rnd), name("asom-node", node.nik.spki), nowSec - 3600, nowSec + CertTemplates.LEAF_LIFETIME_SEC, name("asom-session", node.nik.spki),
            leafKey.spki, leafExtensions(node.nik.spki, true, keyUsage, 2), CertTemplates.ECDSA_SHA256_ALG_ID,
        )
        return HostileChain("ca-leaf", listOf(signedByNik(node, t), node.nodeCert), leafKey.private)
    }

    /** A leaf whose key is P-384 (signed by the node key, as a real leaf would be). */
    fun p384Leaf(node: TestNode, rnd: SplittableRandom, nowSec: Long): HostileChain {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        val t = tbs(
            TestNode.serial(rnd), name("asom-node", node.nik.spki), nowSec - 3600, nowSec + CertTemplates.LEAF_LIFETIME_SEC, name("asom-session", node.nik.spki),
            kp.public.encoded, leafExtensions(node.nik.spki, false, DIGITAL_SIGNATURE, 7), CertTemplates.ECDSA_SHA256_ALG_ID,
        )
        return HostileChain("p384-leaf", listOf(signedByNik(node, t), node.nodeCert), kp.private)
    }

    /** A leaf with an RSA key; it signs CertificateVerify with RSA-PSS wherever the peer allows it. */
    fun rsaLeaf(node: TestNode, rnd: SplittableRandom, nowSec: Long): HostileChain {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val t = tbs(
            TestNode.serial(rnd), name("asom-node", node.nik.spki), nowSec - 3600, nowSec + CertTemplates.LEAF_LIFETIME_SEC, name("asom-session", node.nik.spki),
            kp.public.encoded, leafExtensions(node.nik.spki, false, DIGITAL_SIGNATURE, 7), CertTemplates.ECDSA_SHA256_ALG_ID,
        )
        return HostileChain("rsa-leaf", listOf(signedByNik(node, t), node.nodeCert), kp.private)
    }

    /** A P-256 leaf certified with `ecdsa-with-SHA1`. */
    fun sha1Leaf(node: TestNode, rnd: SplittableRandom, nowSec: Long): HostileChain {
        val leafKey = Es256.generate()
        val sha1 = Der.sequence(Der.oid(Oids.ECDSA_SHA1))
        val t = tbs(
            TestNode.serial(rnd), name("asom-node", node.nik.spki), nowSec - 3600, nowSec + CertTemplates.LEAF_LIFETIME_SEC, name("asom-session", node.nik.spki),
            leafKey.spki, leafExtensions(node.nik.spki, false, DIGITAL_SIGNATURE, 7), sha1,
        )
        val sig = Signature.getInstance("SHA1withECDSA").apply { initSign(node.nik.private); update(t) }.sign()
        return HostileChain("sha1-leaf", listOf(Der.sequence(t, sha1, Der.bitString(sig)), node.nodeCert), leafKey.private)
    }

    /** A valid template leaf whose validity ended 6 days ago (more than the 2 h skew). */
    fun expiredLeaf(node: TestNode, rnd: SplittableRandom, nowSec: Long): HostileChain {
        val leaf = CertTemplates.leafCertificate(node.nik.private, node.nik.spki, node.leafKey.spki, TestNode.serial(rnd), nowSec - 20L * 86_400)
        return HostileChain("expired-leaf", listOf(leaf, node.nodeCert), node.leafKey.private)
    }

    /** A leaf that names [node] as issuer but is signed by [signer]'s node key. */
    fun leafSignedByOtherKey(node: TestNode, signer: TestNode, rnd: SplittableRandom, nowSec: Long): HostileChain {
        val leafKey = Es256.generate()
        val t = CertTemplates.leafTbs(node.nik.spki, leafKey.spki, TestNode.serial(rnd), nowSec)
        val leaf = CertTemplates.assemble(t, Es256.sign(signer.nik.private, t))
        return HostileChain("leaf-signed-by-other-nik", listOf(leaf, node.nodeCert), leafKey.private)
    }

    /** A leaf of one node under the node certificate of another. */
    fun mismatchedPair(leafOf: TestNode, nodeOf: TestNode): HostileChain = HostileChain("mismatched-leaf-and-node", listOf(leafOf.leafCert, nodeOf.nodeCert), leafOf.leafKey.private)

    /** Only the leaf, no node certificate. */
    fun leafOnly(node: TestNode): HostileChain = HostileChain("leaf-only", listOf(node.leafCert), node.leafKey.private)

    /** A well-formed chain of a node nobody paired. */
    fun unpaired(node: TestNode): HostileChain = HostileChain("unpaired-node", listOf(node.leafCert, node.nodeCert), node.leafKey.private)

    fun honest(node: TestNode): HostileChain = HostileChain("honest-chain", listOf(node.leafCert, node.nodeCert), node.leafKey.private)

    fun nothing(): HostileChain = HostileChain("no-chain", null, null)
}

private fun x509(der: ByteArray): X509Certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

/** A key manager that presents whatever it is told to, whatever ALPN or key type was negotiated. This is what a hostile node looks like to the JSSE. */
class HostileKeyManager(private val hostile: HostileChain) : X509ExtendedKeyManager() {
    private val certs: Array<X509Certificate>? = hostile.chain?.map { x509(it) }?.toTypedArray()
    val aliasRequests = AtomicInteger()

    private fun fits(keyType: String?): Boolean {
        val k = hostile.key ?: return false
        return when (k.algorithm) {
            "RSA" -> keyType == "RSA" || keyType == "RSASSA-PSS"
            "EC" -> keyType == "EC"
            else -> false
        }
    }

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<Principal>?, engine: SSLEngine?): String? {
        aliasRequests.incrementAndGet()
        return if (certs != null && fits(keyType)) "h" else null
    }

    override fun chooseEngineClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, engine: SSLEngine?): String? {
        aliasRequests.incrementAndGet()
        return if (certs != null && keyType.orEmpty().any { fits(it) }) "h" else null
    }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = certs
    override fun getPrivateKey(alias: String?): PrivateKey? = hostile.key
    override fun getClientAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?): String? = null
    override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?): String? = null
}

/** A trust manager that accepts anything and counts how often it was asked: a hostile node that trusts everyone, so a refusal is always the honest side's. */
class AcceptAllTrustManager : X509ExtendedTrustManager() {
    val calls = AtomicInteger()
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?, engine: SSLEngine?) { calls.incrementAndGet() }
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?, engine: SSLEngine?) { calls.incrementAndGet() }
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?, socket: Socket?) { calls.incrementAndGet() }
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?, socket: Socket?) { calls.incrementAndGet() }
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) { calls.incrementAndGet() }
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) { calls.incrementAndGet() }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** How a hostile engine is configured. The defaults are the honest mesh profile, so a case changes exactly one thing. */
class HostileSpec(
    val chain: HostileChain,
    val protocols: Array<String> = arrayOf("TLSv1.3"),
    val alpn: Array<String>? = arrayOf(MeshTlsProfile.ALPN),
    val needClientAuth: Boolean = true,
    val peerHost: String? = null,
    val serverNames: List<String>? = null,
    val context: HostileContext? = null,
    val endpointIdentification: String? = null,
    val noKeyManager: Boolean = false,
)

/** A reusable context of a hostile node: the same `SSLContext` (and so the same client session cache) across connections, which is what resumption needs. */
class HostileContext(val chain: HostileChain, noKeyManager: Boolean = false, keyManagerOverride: X509ExtendedKeyManager? = null) {
    val keyManager: X509ExtendedKeyManager = keyManagerOverride ?: HostileKeyManager(chain)
    val trustManager = AcceptAllTrustManager()
    val ssl: SSLContext = SSLContext.getInstance("TLS").also {
        it.init(if (noKeyManager) null else arrayOf<KeyManager>(keyManager), arrayOf<TrustManager>(trustManager), SecureRandom())
    }
}

/** What a hostile engine saw. */
class HostileResult(
    val established: Boolean,
    val handshakeError: Throwable?,
    val alertAfterHandshake: TlsAlert?,
    val readError: Throwable?,
    val trustCalls: Int,
    val alpn: String?,
    val protocol: String?,
    val bytesRead: Int,
)

object Hostile {
    private val alertText = Regex("received fatal alert: ([a-z_0-9]+)")

    fun alertOf(e: Throwable?): TlsAlert? = e?.message?.lowercase()?.let { alertText.find(it) }?.let { TlsAlert.ofName(it.groupValues[1]) }

    /**
     * Drives a hostile engine on [net] with [TlsEngineIo]. After a successful handshake it reads once (a client waits for the honest server's verdict,
     * which in TLS 1.3 arrives after the client already considers itself connected; a server waits for the honest client's first byte).
     * [afterHandshake] runs on success before that read, for instance to write a byte so the peer is not left waiting.
     */
    fun run(
        net: NetChannel, client: Boolean, spec: HostileSpec, afterHandshake: (TlsEngineIo) -> Unit = {}, readAfter: Boolean = true, afterRead: (TlsEngineIo) -> Unit = {},
    ): HostileResult {
        val ctx = spec.context ?: HostileContext(spec.chain, spec.noKeyManager)
        val engine = if (spec.peerHost != null) ctx.ssl.createSSLEngine(spec.peerHost, 4000) else ctx.ssl.createSSLEngine()
        engine.useClientMode = client
        engine.enabledProtocols = spec.protocols
        val p: SSLParameters = engine.sslParameters
        if (spec.alpn != null) p.applicationProtocols = spec.alpn
        if (!client) p.needClientAuth = spec.needClientAuth
        if (spec.endpointIdentification != null) p.endpointIdentificationAlgorithm = spec.endpointIdentification
        if (spec.serverNames != null) p.serverNames = spec.serverNames.map { javax.net.ssl.SNIHostName(it) }
        engine.sslParameters = p
        val io = TlsEngineIo(net, engine)
        var established = false
        var hsError: Throwable? = null
        var readError: Throwable? = null
        var bytes = 0
        try {
            io.handshake(System.nanoTime() + 10_000_000_000L)
            established = true
        } catch (e: Throwable) {
            hsError = e
        }
        if (established) {
            try {
                afterHandshake(io)
                if (readAfter) {
                    val buf = ByteArray(64)
                    val n = io.read(buf, 0, buf.size)
                    if (n > 0) bytes += n
                }
                afterRead(io)
            } catch (e: Throwable) {
                readError = e
            }
        }
        val alpn = if (established) engine.applicationProtocol else null
        val proto = if (established) engine.session.protocol else null
        val tm = ctx.trustManager.calls.get()
        io.failClose()
        return HostileResult(established, hsError, alertOf(readError), readError, tm, alpn, proto, bytes)
    }
}
