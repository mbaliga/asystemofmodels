package xyz.mdhv.asom.lab.proto.integration

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import xyz.mdhv.asom.lab.proto.tls.MeshTlsException
import xyz.mdhv.asom.lab.proto.tls.MeshTlsRefusal

/**
 * The `DIAL` outcome of LAB_SPEC 7.6 for a failed dial, from what the TCP connect and the TLS handshake reported. The set is closed
 * (`connected | refused | timeout | pin-mismatch | not-tls | local-network-denied | firewall-blocked`); this maps only what a JVM can tell apart, and
 * `local-network-denied` and `firewall-blocked` are produced by platform layers this lab does not have.
 */
object DialOutcomes {
    const val CONNECTED = "connected"
    const val REFUSED = "refused"
    const val TIMEOUT = "timeout"
    const val PIN_MISMATCH = "pin-mismatch"
    const val NOT_TLS = "not-tls"

    fun of(e: Throwable): String = when (e) {
        is MeshTlsException -> of(e.refusal)
        is ConnectException -> REFUSED
        is NoRouteToHostException -> REFUSED
        is SocketTimeoutException -> TIMEOUT
        else -> REFUSED
    }

    /**
     * A chain our verifier refused (or a server with nothing to present) is the dialled pin not being there: `pin-mismatch`. A peer that does not speak the
     * profile (no ALPN, no TLS 1.3, no client-auth request, a failed handshake, a close before it ended) is `not-tls`. A peer that refused US with an alert,
     * and any socket failure, is `refused`. The 5 s budget is `timeout`.
     */
    fun of(r: MeshTlsRefusal): String = when (r) {
        MeshTlsRefusal.PEER_CHAIN_REJECTED, MeshTlsRefusal.PEER_CERTIFICATE_MISSING -> PIN_MISMATCH
        MeshTlsRefusal.ALPN_MISSING, MeshTlsRefusal.ALPN_MISMATCH, MeshTlsRefusal.PROTOCOL_VERSION, MeshTlsRefusal.CLIENT_AUTH_NOT_REQUESTED,
        MeshTlsRefusal.HANDSHAKE_FAILED, MeshTlsRefusal.PEER_CLOSED,
        -> NOT_TLS
        MeshTlsRefusal.HANDSHAKE_TIMEOUT -> TIMEOUT
        MeshTlsRefusal.PEER_ALERT, MeshTlsRefusal.TRANSPORT_IO -> REFUSED
    }
}
