package xyz.mdhv.asom.lab.proto.tls

import java.io.IOException
import javax.net.ssl.SSLParameters
import xyz.mdhv.asom.lab.proto.trust.ChainReject

/**
 * The mesh TLS profile (trust.md 3.2, LAB_SPEC 7.4) as constants plus the one function that applies it to an engine's [SSLParameters].
 * Nothing here is a JVM system property or a security property (design C12): every setting is on the engine or its parameters.
 */
object MeshTlsProfile {
    const val PROTOCOL = "TLSv1.3"
    const val ALPN = "asom-mesh/1"
    const val HANDSHAKE_TIMEOUT_MS = 5_000L
    const val SIGNATURE_SCHEME = "ecdsa_secp256r1_sha256"

    /** How long a close waits for the peer's close_notify before the socket is closed anyway. */
    const val CLOSE_WAIT_MS = 500L

    /**
     * `SSLParameters.setSignatureSchemes` exists from JDK 19 on. It is looked up by reflection so the code compiles and runs on JDK 17,
     * where this knob cannot be set scoped (S-A9): there the signature scheme is enforced one layer up, by the key manager (EC keys only)
     * and by `verifyPeerChain` (P-256 keys and `ecdsa-with-SHA256` only, which makes `ecdsa_secp256r1_sha256` the only usable scheme).
     */
    private val setSignatureSchemes = runCatching { SSLParameters::class.java.getMethod("setSignatureSchemes", Array<String>::class.java) }.getOrNull()

    val signatureSchemesScoped: Boolean get() = setSignatureSchemes != null

    fun apply(p: SSLParameters, client: Boolean) {
        p.protocols = arrayOf(PROTOCOL)
        p.applicationProtocols = arrayOf(ALPN)
        p.endpointIdentificationAlgorithm = null
        if (client) {
            p.serverNames = emptyList()
        } else {
            p.needClientAuth = true
        }
        setSignatureSchemes?.invoke(p, arrayOf(SIGNATURE_SCHEME))
    }
}

/** A TLS alert description (RFC 8446 section 6), by name. [OTHER] is any name this table does not list. */
enum class TlsAlert(val code: Int) {
    CLOSE_NOTIFY(0), UNEXPECTED_MESSAGE(10), BAD_RECORD_MAC(20), RECORD_OVERFLOW(22), HANDSHAKE_FAILURE(40), BAD_CERTIFICATE(42),
    UNSUPPORTED_CERTIFICATE(43), CERTIFICATE_REVOKED(44), CERTIFICATE_EXPIRED(45), CERTIFICATE_UNKNOWN(46), ILLEGAL_PARAMETER(47),
    UNKNOWN_CA(48), ACCESS_DENIED(49), DECODE_ERROR(50), DECRYPT_ERROR(51), PROTOCOL_VERSION(70), INSUFFICIENT_SECURITY(71),
    INTERNAL_ERROR(80), INAPPROPRIATE_FALLBACK(86), USER_CANCELED(90), MISSING_EXTENSION(109), UNSUPPORTED_EXTENSION(110),
    UNRECOGNIZED_NAME(112), BAD_CERTIFICATE_STATUS_RESPONSE(113), UNKNOWN_PSK_IDENTITY(115), CERTIFICATE_REQUIRED(116),
    NO_APPLICATION_PROTOCOL(120), OTHER(-1),
    ;

    companion object {
        fun ofName(name: String): TlsAlert = entries.firstOrNull { it != OTHER && it.name.equals(name, ignoreCase = true) } ?: OTHER
    }
}

/**
 * Every refusal a mesh TLS connection can end in. A refusal is typed: it is never a bare `SSLException` and never carries peer-authored text.
 * Which side saw what is part of the type: [PEER_ALERT] is the peer refusing US, the others are our own decision.
 */
enum class MeshTlsRefusal {
    /** `verifyPeerChain` refused the chain the peer presented; the code is in [MeshTlsException.chainReject]. */
    PEER_CHAIN_REJECTED,

    /** The peer presented no certificate (a client sending an empty Certificate message, or a server with nothing to present). */
    PEER_CERTIFICATE_MISSING,

    /** No ALPN was negotiated at all. A silent fallback to "no ALPN" is never allowed. */
    ALPN_MISSING,

    /** An ALPN other than `asom-mesh/1` was offered or selected. */
    ALPN_MISMATCH,

    /** The peer will not speak TLS 1.3, or the negotiated version was not TLS 1.3. */
    PROTOCOL_VERSION,

    /** The server never asked for our certificate: mutual authentication did not happen, so the session is refused (client side only). */
    CLIENT_AUTH_NOT_REQUESTED,

    /** The 5 s budget for the whole handshake ran out. */
    HANDSHAKE_TIMEOUT,

    /** The peer ended the handshake with a fatal alert; the alert is in [MeshTlsException.peerAlert]. */
    PEER_ALERT,

    /** The peer closed the connection (or sent close_notify) before the handshake finished. */
    PEER_CLOSED,

    /** Any other local handshake failure: our own key manager had nothing to present, a malformed record, and the like. */
    HANDSHAKE_FAILED,

    /** The socket failed under us. */
    TRANSPORT_IO,
}

/** Where in the life of the connection a refusal happened. */
enum class TlsStage { HANDSHAKE, POST_HANDSHAKE_CHECK, ESTABLISHED }

/** The only exception this package throws for a refusal. Its message is the refusal name, never text taken from the peer or from an exception of the JSSE. */
class MeshTlsException(
    val refusal: MeshTlsRefusal,
    val stage: TlsStage,
    val chainReject: ChainReject? = null,
    val peerAlert: TlsAlert? = null,
) : IOException("mesh tls refusal ${refusal.name}" + (chainReject?.let { " ${it.name}" } ?: "") + (peerAlert?.let { " ${it.name}" } ?: ""))
