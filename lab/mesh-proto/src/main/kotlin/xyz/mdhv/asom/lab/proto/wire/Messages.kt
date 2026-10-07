package xyz.mdhv.asom.lab.proto.wire

import xyz.mdhv.asom.lab.policy.Fsm
import xyz.mdhv.asom.lab.policy.Governor
import xyz.mdhv.asom.lab.policy.StateDoc

interface WireEnum {
    val wire: String
}

internal fun <T> Iterable<T>.byWire(text: String): T? where T : Enum<T>, T : WireEnum = firstOrNull { it.wire == text }

enum class Via(override val wire: String) : WireEnum { LAN("lan"), OVERLAY("overlay") }

enum class KeyTier(override val wire: String) : WireEnum {
    STRONGBOX("strongbox"), TEE("tee"), SECURE_ENCLAVE("secure-enclave"), TPM("tpm"), OS_KEYSTORE("os-keystore"), FILE("file"),
}

enum class Platform(override val wire: String) : WireEnum {
    ANDROID("android"), IOS("ios"), IPADOS("ipados"), MACOS("macos"), LINUX("linux"), WINDOWS("windows"), UBUNTU_TOUCH("ubuntu-touch"),
}

enum class Feature(override val wire: String) : WireEnum { INFER_OFFER("infer.offer"), MANIFEST("manifest"), STATE("state") }

enum class Scope(override val wire: String) : WireEnum { INFER("infer"), MANIFEST("manifest"), STATE("state") }

enum class GoAwayReason(override val wire: String) : WireEnum {
    REVOKED("revoked"), SUSPENDED("suspended"), SHUTDOWN("shutdown"), NETWORK_CHANGE("network-change"), IDLE("idle"), MAX_AGE("max-age"),
}

enum class InferOp(override val wire: String) : WireEnum { CHAT("chat"), COMPLETIONS("completions"), EMBEDDINGS("embeddings") }

enum class DeclineWire(override val wire: String) : WireEnum {
    PEER_BUSY("PEER_BUSY"), PEER_UNAVAILABLE("PEER_UNAVAILABLE"), MODEL_NOT_OFFERED("MODEL_NOT_OFFERED"), SCOPE_DENIED("SCOPE_DENIED"), DUPLICATE_ATTEMPT("DUPLICATE_ATTEMPT"),
}

enum class Terminal(override val wire: String) : WireEnum { DONE("done"), CANCELLED("cancelled"), INTERRUPTED("interrupted"), OOM("oom"), ERROR("error") }

enum class CancelReason(override val wire: String) : WireEnum {
    CLIENT_GONE("client-gone"), DEADLINE("deadline"), POLICY_CHANGED("policy-changed"), SUPERSEDED("superseded"),
}

data class Endpoint(val addr: String, val port: Int, val via: Via) {
    init {
        if (!IpLiteral.isValid(addr) || port !in 1..65535) bad(Reason.OUT_OF_RANGE)
    }
}

/** The `st` digest of `HELLO_ACK`, `INFER_ACCEPT`, `INFER_DECLINE` and `INFER_END`: only to a peer holding scope `state`. */
data class St(val fsm: Fsm, val gov: Governor, val qb: Int, val seq: Long, val tb: Int) {
    init {
        if (qb !in 0..2 || tb !in 0..2 || seq < 1 || !Rules.isCount(seq)) bad(Reason.OUT_OF_RANGE)
    }
}

data class Limits(val idleUnloadMs: Long, val maxBodyBytes: Long, val maxConcurrent: Long, val maxTokens: Long, val rpm: Long) {
    init {
        if (!listOf(idleUnloadMs, maxBodyBytes, maxConcurrent, maxTokens, rpm).all { Rules.isCount(it) }) bad(Reason.OUT_OF_RANGE)
    }
}

/** A received `ERROR` is reduced to its closed code, an optional attempt and an optional retry hint. There is no member for peer-authored text, by design (T15). */
data class PeerError(val code: MeshError, val attemptId: String? = null, val retryAfterMs: Long? = null) : Message {
    init {
        if ((attemptId != null && !Rules.isAttemptId(attemptId)) || (retryAfterMs != null && !Rules.isCount(retryAfterMs))) bad(Reason.OUT_OF_RANGE)
    }

    override val type: Int get() = FrameTypes.ERROR

    val effective: MeshError get() = code.handledAs
}

sealed interface Message {
    val type: Int
}

data class Hello(
    val endpoints: List<Endpoint>,
    val features: Set<Feature>,
    val keyTier: KeyTier,
    val minV: Int,
    val maxV: Int,
    val name: String,
    val nodeId: String,
    val platform: Platform,
    val sessionNonce: String,
    val sw: String,
    val ts: Long,
    val v: Int,
) : Message {
    init {
        if (endpoints.size > Rules.MAX_ENDPOINTS) bad(Reason.OUT_OF_RANGE)
        if (minV !in 1..255 || maxV !in minV..255) bad(Reason.OUT_OF_RANGE)
        if (v !in minV..maxV) bad(Reason.BAD_VERSION)
        if (!Rules.isName(name) || !Rules.isNodeId(nodeId) || !Rules.isSessionNonce(sessionNonce) || !Rules.isSoftware(sw) || !Rules.isCount(ts)) bad(Reason.OUT_OF_RANGE)
    }

    override val type: Int get() = FrameTypes.HELLO
    val versions: VersionRange get() = VersionRange(minV, maxV)
}

data class HelloAck(
    val endpoints: List<Endpoint>,
    val features: Set<Feature>,
    val granted: Set<Scope>,
    val limits: Limits,
    val nodeId: String,
    val st: St?,
    val ts: Long,
    val v: Int,
) : Message {
    init {
        if (endpoints.size > Rules.MAX_ENDPOINTS || v !in 1..255) bad(Reason.OUT_OF_RANGE)
        if (!Rules.isNodeId(nodeId) || !Rules.isCount(ts)) bad(Reason.OUT_OF_RANGE)
    }

    override val type: Int get() = FrameTypes.HELLO_ACK
}

data class GoAway(val reason: GoAwayReason) : Message {
    override val type: Int get() = FrameTypes.GOAWAY
}

data class InferOffer(
    val attemptId: String,
    val deadlineMs: Long,
    val estTokensIn: Long,
    val maxTokens: Long,
    val model: String,
    val op: InferOp,
    val promptBytes: Long,
    val stream: Boolean,
) : Message {
    init {
        if (!Rules.isAttemptId(attemptId) || !Rules.isModelId(model)) bad(Reason.OUT_OF_RANGE)
        if (!listOf(deadlineMs, estTokensIn, maxTokens, promptBytes).all { Rules.isCount(it) }) bad(Reason.OUT_OF_RANGE)
    }

    override val type: Int get() = FrameTypes.INFER_OFFER
}

data class InferAccept(val attemptId: String, val fileSha256: String, val servedModel: String, val st: St? = null) : Message {
    init {
        if (!Rules.isAttemptId(attemptId) || !Rules.isSha256Hex(fileSha256) || !Rules.isModelId(servedModel)) bad(Reason.OUT_OF_RANGE)
    }

    override val type: Int get() = FrameTypes.INFER_ACCEPT
}

data class InferDecline(val attemptId: String, val code: DeclineWire, val retryAfterMs: Long, val st: St? = null) : Message {
    init {
        if (!Rules.isAttemptId(attemptId) || retryAfterMs !in RETRY_MIN..RETRY_MAX) bad(Reason.OUT_OF_RANGE)
    }

    override val type: Int get() = FrameTypes.INFER_DECLINE

    companion object {
        const val RETRY_MIN = 5_000L
        const val RETRY_MAX = 600_000L
    }
}

class InferBody(val bytes: ByteArray) : Message {
    override val type: Int get() = FrameTypes.INFER_BODY

    override fun equals(other: Any?): Boolean = other is InferBody && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()
}

data class InferHead(val attemptId: String, val servedModel: String, val status: Int) : Message {
    init {
        if (!Rules.isAttemptId(attemptId) || !Rules.isModelId(servedModel) || status !in 100..599) bad(Reason.OUT_OF_RANGE)
    }

    override val type: Int get() = FrameTypes.INFER_HEAD
    val engine: String get() = "local"
}

class InferChunk(val bytes: ByteArray) : Message {
    override val type: Int get() = FrameTypes.INFER_CHUNK

    override fun equals(other: Any?): Boolean = other is InferChunk && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()
}

data class InferEnd(val attemptId: String, val status: Int, val terminal: Terminal, val st: St? = null) : Message {
    init {
        if (!Rules.isAttemptId(attemptId) || status !in 100..599) bad(Reason.OUT_OF_RANGE)
    }

    override val type: Int get() = FrameTypes.INFER_END
}

data class Cancel(val attemptId: String, val reason: CancelReason) : Message {
    init {
        if (!Rules.isAttemptId(attemptId)) bad(Reason.OUT_OF_RANGE)
    }

    override val type: Int get() = FrameTypes.CANCEL
}

data object StateReq : Message {
    override val type: Int get() = FrameTypes.STATE_REQ
}

/** `asom.state/1`. The document type is `:mesh-policy`'s [StateDoc], which has no presence field to fill (LP-1). */
data class StateMsg(val doc: StateDoc) : Message {
    override val type: Int get() = FrameTypes.STATE
}

data class ManifestReq(val challenge: String) : Message {
    init {
        if (!Rules.isChallenge(challenge)) bad(Reason.OUT_OF_RANGE)
    }

    override val type: Int get() = FrameTypes.MANIFEST_REQ
}

/** The container of manifest 4.3 as received: strict JSON, object-shaped, and otherwise opaque to the wire layer (`:manifest` verifies it). */
class ManifestMsg(val container: ByteArray) : Message {
    override val type: Int get() = FrameTypes.MANIFEST

    override fun equals(other: Any?): Boolean = other is ManifestMsg && container.contentEquals(other.container)

    override fun hashCode(): Int = container.contentHashCode()
}

data object RevokeNotice : Message {
    override val type: Int get() = FrameTypes.REVOKE_NOTICE
}

/** A `PAIR_*` frame at the wire layer: the type number and the raw payload. The bodies belong to the pairing track, through [PairPayloadHook]. */
class PairMsg(override val type: Int, val payload: ByteArray) : Message {
    init {
        require(FrameTypes.isPair(type)) { "not a PAIR_* type" }
    }

    override fun equals(other: Any?): Boolean = other is PairMsg && type == other.type && payload.contentEquals(other.payload)

    override fun hashCode(): Int = type * 31 + payload.contentHashCode()
}

/** The codec hook for pairing message bodies: return null to accept the payload, or a reason to refuse it with `PROTOCOL_ERROR`. */
fun interface PairPayloadHook {
    fun check(type: Int, payload: ByteArray): String?
}
