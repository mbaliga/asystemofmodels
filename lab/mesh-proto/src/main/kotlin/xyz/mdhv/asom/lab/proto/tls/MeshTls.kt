package xyz.mdhv.asom.lab.proto.tls

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.channels.SocketChannel
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import xyz.mdhv.asom.lab.proto.transport.MeshConnection
import xyz.mdhv.asom.lab.proto.transport.TransportCounters
import xyz.mdhv.asom.lab.proto.trust.ChainMode
import xyz.mdhv.asom.lab.proto.trust.ChainVerdict
import xyz.mdhv.asom.lab.proto.trust.Pin
import xyz.mdhv.asom.lab.proto.wire.ConnMode
import xyz.mdhv.asom.lab.proto.wire.PeerRole

/** What the connection negotiated, as the engine reported it after the handshake and after the profile checks passed. */
class TlsFacts(
    val protocol: String,
    val cipherSuite: String,
    val alpn: String,
    val peerChainLength: Int,
    val trustInvocations: Int,
    val endpointIdentification: String?,
    val serverNames: List<String>,
)

/**
 * An established mesh connection: TLS 1.3, both certificates verified by `verifyPeerChain`, ALPN `asom-mesh/1`. The streams are plain bytes (frames are
 * not parsed here). [peerPin] follows the [MeshConnection] contract and is null in PAIRING mode; [verifiedPin] is the pin the verifier proved in every
 * mode (an ERRATA request: the pairing layer needs it, and the contract file may not change).
 *
 * Every failure on the streams is a [MeshTlsException]; a clean end of stream (the peer's close_notify) is `-1`.
 */
class TlsMeshConnection internal constructor(
    private val io: TlsEngineIo,
    override val role: PeerRole,
    override val mode: ConnMode,
    val verifiedPin: Pin,
    val observer: HandshakeObserver,
    val facts: TlsFacts,
) : MeshConnection {
    override val peerPin: Pin? get() = if (mode == ConnMode.PAIRING) null else verifiedPin

    override val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            while (true) {
                val n = read(one, 0, 1)
                if (n < 0) return -1
                if (n == 1) return one[0].toInt() and 0xFF
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            java.util.Objects.checkFromIndexSize(off, len, b.size)
            try {
                return io.read(b, off, len)
            } catch (e: Exception) {
                throw MeshTls.translate(e, TlsStage.ESTABLISHED, observer)
            }
        }

        override fun close() = this@TlsMeshConnection.close()
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            java.util.Objects.checkFromIndexSize(off, len, b.size)
            try {
                io.write(b, off, len)
            } catch (e: Exception) {
                throw MeshTls.translate(e, TlsStage.ESTABLISHED, observer)
            }
        }

        override fun close() = this@TlsMeshConnection.close()
    }

    /** Network bytes exactly as the engine reported them (sums of `bytesConsumed` and `bytesProduced`). Final once the connection is closed. */
    override fun counters(): TransportCounters = TransportCounters(io.networkBytesIn, io.networkBytesOut, measured = true)

    override fun close() = io.close()
}

/**
 * Builds mesh TLS connections over a [NetChannel]. Every call creates a FRESH `SSLContext` with its own key manager, trust manager and (empty) session
 * caches, for the dialer and for the listener alike: no client session cache can hold a ticket to offer, and a ticket a client brings to a listener
 * cannot be redeemed, because the context that issued it is gone (S-A9). Nothing here opens a listener; the host hands in a connected channel.
 */
object MeshTls {
    /** Dials: the channel is connected to the peer and [expect] says whom to expect (ExpectPaired, or ExpectPairing for a QR pin). */
    fun dial(
        net: NetChannel,
        identity: MeshIdentity,
        expect: ChainMode,
        env: MeshTlsEnv,
        observer: HandshakeObserver = HandshakeObserver(),
    ): TlsMeshConnection {
        require(expect is ChainMode.ExpectPaired || expect is ChainMode.ExpectPairing) { "a dialler expects a paired pin or the pin of a QR" }
        return establish(net, identity, env, expect, observer)
    }

    /** Accepts: the channel is an accepted connection. The verifier chooses ESTABLISHED_SERVER or PAIRING_SERVER (trust.md 3.2). */
    fun accept(net: NetChannel, identity: MeshIdentity, env: MeshTlsEnv, observer: HandshakeObserver = HandshakeObserver()): TlsMeshConnection =
        establish(net, identity, env, null, observer)

    /** [dial] on a connected `SocketChannel`; the channel is switched to non-blocking mode and owned by the connection from here on. */
    fun dial(channel: SocketChannel, identity: MeshIdentity, expect: ChainMode, env: MeshTlsEnv): TlsMeshConnection = dial(SocketNet(channel), identity, expect, env)

    /** [accept] on an accepted `SocketChannel`; the channel is owned by the connection from here on. */
    fun accept(channel: SocketChannel, identity: MeshIdentity, env: MeshTlsEnv): TlsMeshConnection = accept(SocketNet(channel), identity, env)

    private fun establish(net: NetChannel, identity: MeshIdentity, env: MeshTlsEnv, expect: ChainMode?, observer: HandshakeObserver): TlsMeshConnection {
        val client = expect != null
        var stage = TlsStage.HANDSHAKE
        val io: TlsEngineIo
        try {
            val ctx = SSLContext.getInstance(MeshTlsProfile.PROTOCOL)
            ctx.init(arrayOf(MeshKeyManager(identity, observer)), arrayOf(MeshTrustManager(expect, env, observer)), SecureRandom())
            val engine = ctx.createSSLEngine()
            engine.useClientMode = client
            engine.enabledProtocols = arrayOf(MeshTlsProfile.PROTOCOL)
            val params = engine.sslParameters
            MeshTlsProfile.apply(params, client)
            engine.sslParameters = params
            io = TlsEngineIo(net, engine, env.writeStallMs)
        } catch (e: Exception) {
            net.close()
            throw translate(e, stage, observer)
        }
        val deadline = System.nanoTime() + MeshTlsProfile.HANDSHAKE_TIMEOUT_MS * 1_000_000L
        try {
            io.handshake(deadline)
            stage = TlsStage.POST_HANDSHAKE_CHECK
            return check(io, client, expect, observer)
        } catch (e: Exception) {
            val refusal = translate(e, stage, observer)
            io.failClose()
            throw refusal
        }
    }

    /** The checks that do not depend on a JSSE knob: ALPN, version, chain presence and the verifier's verdict. Any failure closes the connection. */
    private fun check(io: TlsEngineIo, client: Boolean, expect: ChainMode?, observer: HandshakeObserver): TlsMeshConnection {
        val engine = io.engine
        val session = engine.session
        val alpn = engine.applicationProtocol.orEmpty()
        if (alpn.isEmpty()) throw MeshTlsException(MeshTlsRefusal.ALPN_MISSING, TlsStage.POST_HANDSHAKE_CHECK)
        if (alpn != MeshTlsProfile.ALPN) throw MeshTlsException(MeshTlsRefusal.ALPN_MISMATCH, TlsStage.POST_HANDSHAKE_CHECK)
        if (session.protocol != MeshTlsProfile.PROTOCOL) throw MeshTlsException(MeshTlsRefusal.PROTOCOL_VERSION, TlsStage.POST_HANDSHAKE_CHECK)
        val chain = try {
            session.peerCertificates
        } catch (e: SSLException) {
            throw MeshTlsException(MeshTlsRefusal.PEER_CERTIFICATE_MISSING, TlsStage.POST_HANDSHAKE_CHECK)
        }
        if (client && !observer.certificateRequested) throw MeshTlsException(MeshTlsRefusal.CLIENT_AUTH_NOT_REQUESTED, TlsStage.POST_HANDSHAKE_CHECK)
        val verdict = observer.verdict as? ChainVerdict.Accepted ?: throw MeshTlsException(MeshTlsRefusal.HANDSHAKE_FAILED, TlsStage.POST_HANDSHAKE_CHECK)
        if (chain.size != 2 || observer.trustInvocations < 1) throw MeshTlsException(MeshTlsRefusal.HANDSHAKE_FAILED, TlsStage.POST_HANDSHAKE_CHECK)
        val mode = if (client) {
            if (expect is ChainMode.ExpectPairing) ConnMode.PAIRING else ConnMode.ESTABLISHED
        } else {
            if (verdict.mode == ChainMode.PairingServer) ConnMode.PAIRING else ConnMode.ESTABLISHED
        }
        session.invalidate()
        val params = engine.sslParameters
        val facts = TlsFacts(
            session.protocol, session.cipherSuite, alpn, chain.size, observer.trustInvocations, params.endpointIdentificationAlgorithm,
            params.serverNames.orEmpty().map { (it as? javax.net.ssl.SNIHostName)?.asciiName ?: "other" },
        )
        return TlsMeshConnection(io, if (client) PeerRole.TLS_CLIENT else PeerRole.TLS_SERVER, mode, verdict.pin, observer, facts)
    }

    private val alertText = Regex("received fatal alert: ([a-z_0-9]+)")

    /**
     * Names a failure. Our own decisions come first (the verifier's verdict, then a gate of the key manager), because JDK 17 and JDK 21 word their
     * local errors differently and the text is not a contract; the text is read only to recognise a received alert and a few JSSE refusals, and it is
     * never stored or echoed (T15).
     */
    internal fun translate(e: Throwable, stage: TlsStage, observer: HandshakeObserver): MeshTlsException {
        if (e is MeshTlsException) return e
        (observer.verdict as? ChainVerdict.Rejected)?.let { return MeshTlsException(MeshTlsRefusal.PEER_CHAIN_REJECTED, stage, chainReject = it.code) }
        observer.gate?.let { return MeshTlsException(it, stage) }
        if (e is SocketTimeoutException) return MeshTlsException(if (stage == TlsStage.ESTABLISHED) MeshTlsRefusal.TRANSPORT_IO else MeshTlsRefusal.HANDSHAKE_TIMEOUT, stage)
        if (e is PeerEof) return MeshTlsException(MeshTlsRefusal.PEER_CLOSED, stage)
        if (e is SSLException) {
            val m = (e.message ?: "").lowercase()
            alertText.find(m)?.let { return MeshTlsException(MeshTlsRefusal.PEER_ALERT, stage, peerAlert = TlsAlert.ofName(it.groupValues[1])) }
            return when {
                "no_application_protocol" in m || "application layer protocol" in m -> MeshTlsException(MeshTlsRefusal.ALPN_MISMATCH, stage)
                "protocol_version" in m || ("protocol versions" in m && "not accepted" in m) -> MeshTlsException(MeshTlsRefusal.PROTOCOL_VERSION, stage)
                "empty client certificate chain" in m || "certificate_required" in m -> MeshTlsException(MeshTlsRefusal.PEER_CERTIFICATE_MISSING, stage)
                "close_notify" in m || "closed inbound" in m -> MeshTlsException(MeshTlsRefusal.PEER_CLOSED, stage)
                else -> MeshTlsException(MeshTlsRefusal.HANDSHAKE_FAILED, stage)
            }
        }
        if (e is IOException) return MeshTlsException(MeshTlsRefusal.TRANSPORT_IO, stage)
        return MeshTlsException(MeshTlsRefusal.HANDSHAKE_FAILED, stage)
    }
}
