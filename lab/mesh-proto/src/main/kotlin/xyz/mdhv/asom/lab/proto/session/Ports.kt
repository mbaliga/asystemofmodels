package xyz.mdhv.asom.lab.proto.session

import java.security.SecureRandom
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.ledger.PeerPath
import xyz.mdhv.asom.lab.manifest.Verified
import xyz.mdhv.asom.lab.manifest.VerifyContext
import xyz.mdhv.asom.lab.policy.LenderLocalView
import xyz.mdhv.asom.lab.proto.transport.MeshConnection
import xyz.mdhv.asom.lab.proto.trust.Pin
import xyz.mdhv.asom.lab.proto.wire.Endpoint
import xyz.mdhv.asom.lab.proto.wire.Feature
import xyz.mdhv.asom.lab.proto.wire.InferOp
import xyz.mdhv.asom.lab.proto.wire.KeyTier
import xyz.mdhv.asom.lab.proto.wire.Limits
import xyz.mdhv.asom.lab.proto.wire.Platform
import xyz.mdhv.asom.lab.proto.wire.Terminal
import xyz.mdhv.asom.lab.proto.wire.VersionRange

/** What this node announces and enforces. Every value is configuration, not a measurement (PROVISIONAL where the spec gives none). */
class NodeConfig(
    val pin: Pin,
    val name: String,
    val platform: Platform,
    val keyTier: KeyTier,
    val sw: String,
    val versions: VersionRange = VersionRange(1, 1),
    val features: Set<Feature> = Feature.entries.toSet(),
    val endpoints: List<Endpoint> = emptyList(),
    val limits: Limits = Limits(idleUnloadMs = 300_000, maxBodyBytes = 8_388_608, maxConcurrent = 1, maxTokens = 4_096, rpm = 30),
    /** A HELLO or HELLO_ACK `ts` further than this from the local clock is `CLOCK_SKEW`. 2 h is the certificate allowance of trust.md 3.2 (ERRATA ERR-PS-6). */
    val clockSkewMs: Long = 7_200_000,
    val handshakeTimeoutMs: Long = 5_000,
    val productionKeys: Boolean = true,
)

/** Randomness and clock, injected so that every run is reproducible. */
interface IdSource {
    fun bytes(n: Int): ByteArray

    fun b64(n: Int): String = Base64Strict.encodeUrlNoPad(bytes(n))
}

class SecureIds(private val rnd: SecureRandom = SecureRandom()) : IdSource {
    override fun bytes(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }
}

// ------------------------------------------------------------------------------------------------------------------ the engine port

class ModelOffer(val servedModel: String, val fileSha256: String)

class EngineRequest(val attemptId: String, val model: String, val op: InferOp, val stream: Boolean, val body: ByteArray)

sealed interface EngineEvent {
    class Head(val status: Int, val servedModel: String) : EngineEvent

    class Chunk(val bytes: ByteArray) : EngineEvent

    class End(val terminal: Terminal, val status: Int, val tokensIn: Long?) : EngineEvent
}

/** One running generation. [next] returns the next event (and `End` last); [cancel] asks it to stop, after which `next` ends the run with `cancelled`. */
interface EngineRun {
    fun next(): EngineEvent

    fun cancel()
}

/** The lender's engine. There is no real engine in v1: [NoopEngine] offers nothing and nothing here generates tokens. */
interface EnginePort {
    /** The model as this node would serve it, or null when it is not allowed or not loadable (decision row 4). */
    fun offered(model: String): ModelOffer?

    /** Called only after the lender intent row is durable (L-L2). */
    fun open(request: EngineRequest): EngineRun
}

object NoopEngine : EnginePort {
    override fun offered(model: String): ModelOffer? = null

    override fun open(request: EngineRequest): EngineRun = throw IllegalStateException("NoopEngine runs nothing")
}

// ------------------------------------------------------------------------------------------------------------------ policy, live state, manifests

/** The lender's own serving situation for decision rows 6, 6a and 6b. A PRESENCE cause may change a decision, never anything else on the wire (LP-1). */
class ServingView(
    val servingConditionsOk: Boolean = true,
    val presenceActive: Boolean = false,
    val presenceHoldRemainingMs: Long = 0,
    val predictedThermalHold: Boolean = false,
    val estStartMs: Long = 0,
)

fun interface LenderPolicyPort {
    fun view(): ServingView
}

object OpenPolicy : LenderPolicyPort {
    override fun view(): ServingView = ServingView()
}

/** Live state for `STATE` and `st` (`:mesh-policy` builds the payload; this only supplies the inputs). */
interface LivePort {
    fun view(): LenderLocalView

    /** A value that rises with every call; it is the `seq` of `STATE` and of `st` (>= 1). */
    fun nextSeq(): Long

    fun sampledAgeMs(): Long
}

/** Signing (the lender side) and verification context (the requester side) of manifests. */
interface ManifestPort {
    /** The signed container answering [challenge], or null when none can be produced (`ERROR MANIFEST_UNAVAILABLE`). */
    fun present(challenge: ByteArray): ByteArray?

    /**
     * The verification context for a manifest received from [peer] answering [challenge] (`Mode.MESH`). `MeshConnection` carries the pin but not the
     * peer's SPKI, so the host supplies it (ERRATA ERR-PS-9); the session refuses an SPKI whose hash is not [peer].
     */
    fun contextFor(peer: Pin, challenge: ByteArray): VerifyContext

    /** Called after a received manifest verified; committing the rollback update is the caller's act (LAB_SPEC 4.6 step 19). */
    fun onVerified(peer: Pin, verified: Verified) {}
}

// ------------------------------------------------------------------------------------------------------------------ dialing

/** What a dial produced: a code from the DIAL outcome set, and the established TLS transport when the code is `connected`. */
class ConnectResult(val code: String, val connection: MeshConnection? = null, val peerPath: PeerPath? = null)

fun interface Dialer {
    fun connect(destAddr: String): ConnectResult
}

/** Lets a test (and only a test) see which inbound frames a session actually dispatched. */
fun interface SessionObserver {
    fun inbound(session: Session, type: Int, stream: Long, payloadBytes: Int)
}
