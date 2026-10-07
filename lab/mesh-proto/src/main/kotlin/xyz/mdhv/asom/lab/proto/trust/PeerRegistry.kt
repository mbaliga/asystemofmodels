package xyz.mdhv.asom.lab.proto.trust

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson
import xyz.mdhv.asom.lab.policy.PeerStatus

/** The `status` column of trust.md 4.7: 2 PAIRED, 3 REVOKED, 4 SUSPENDED. Any other value is corrupt and denies (law L5). */
object StatusCodes {
    const val PAIRED = 2
    const val REVOKED = 3
    const val SUSPENDED = 4

    fun decode(raw: Int): PeerStatus? = when (raw) {
        PAIRED -> PeerStatus.PAIRED
        REVOKED -> PeerStatus.REVOKED
        SUSPENDED -> PeerStatus.SUSPENDED
        else -> null
    }

    fun encode(s: PeerStatus): Int = when (s) {
        PeerStatus.PAIRED -> PAIRED
        PeerStatus.REVOKED -> REVOKED
        PeerStatus.SUSPENDED -> SUSPENDED
    }
}

enum class PeerClass(val wire: String) { OWN("own"), OTHER("other") }

/** The mesh-1 inbound scopes (LAB_SPEC 7.2 `granted`). `revoke-hint` is not among them: the hint frames are retired in mesh-1 (LAB_SPEC 7.1). */
enum class Scope(val wire: String) {
    INFER("infer"), MANIFEST("manifest"), STATE("state");

    companion object {
        fun of(wire: String): Scope? = entries.firstOrNull { it.wire == wire }
    }
}

enum class RouteCeiling { D1, D2 }

/** The reason of a `GOAWAY` the host sends to every session of a peer whose row left PAIRED (LAB_SPEC 7.2). */
enum class GoawayReason(val wire: String) { REVOKED("revoked"), SUSPENDED("suspended") }

/**
 * One persisted row, in the raw column types of trust.md 4.7 so that a corrupt value can exist and be tested. [status] is the integer
 * column; [inboundScopesJson] the JSON array text; the others are as stored.
 */
class StoredRow(
    val pin: Pin,
    val label: String,
    val platform: String,
    val clazz: String,
    val status: Int,
    val inboundScopesJson: String,
    val routeEnabled: Int,
    val routeCeiling: String,
    val pairTranscript: ByteArray?,
    val confirmedByPeer: Boolean,
    val statusReason: String,
    val statusChangedAt: Long,
) {
    fun copy(
        status: Int = this.status, inboundScopesJson: String = this.inboundScopesJson, routeEnabled: Int = this.routeEnabled,
        routeCeiling: String = this.routeCeiling, confirmedByPeer: Boolean = this.confirmedByPeer, statusReason: String = this.statusReason,
        statusChangedAt: Long = this.statusChangedAt,
    ) = StoredRow(pin, label, platform, clazz, status, inboundScopesJson, routeEnabled, routeCeiling, pairTranscript, confirmedByPeer, statusReason, statusChangedAt)
}

sealed interface StoreRead {
    class Present(val row: StoredRow) : StoreRead
    data object Absent : StoreRead
    data object Unreadable : StoreRead
}

/** The DAO behind the registry (the same pattern as v1's `PairingRegistry`). [write] and [delete] report whether the change is durable. */
interface PeerStore {
    fun read(pin: Pin): StoreRead
    fun write(row: StoredRow): Boolean
    fun delete(pin: Pin): Boolean
}

/** An in-memory store with fault injection, for tests and the lab. */
class InMemoryPeerStore : PeerStore {
    private val rows = LinkedHashMap<String, StoredRow>()
    var failReads = false
    var failWrites = false
    private val unreadable = HashSet<String>()

    @Synchronized override fun read(pin: Pin): StoreRead {
        if (failReads || pin.nodeId in unreadable) return StoreRead.Unreadable
        return rows[pin.nodeId]?.let { StoreRead.Present(it) } ?: StoreRead.Absent
    }

    @Synchronized override fun write(row: StoredRow): Boolean {
        if (failWrites) return false
        rows[row.pin.nodeId] = row
        return true
    }

    @Synchronized override fun delete(pin: Pin): Boolean {
        if (failWrites) return false
        rows.remove(pin.nodeId)
        return true
    }

    @Synchronized fun pins(): List<Pin> = rows.values.map { it.pin }

    @Synchronized fun markUnreadable(pin: Pin) { unreadable += pin.nodeId }

    /** Plants a row exactly as given (including a status or scopes value the code does not know). */
    @Synchronized fun plant(row: StoredRow) { rows[row.pin.nodeId] = row }
}

class PairingCeremony(
    val pin: Pin,
    val label: String,
    val platform: String,
    val clazz: PeerClass,
    val transcript: ByteArray,
    val grantedInbound: Set<Scope>,
) {
    var localApproved: Boolean = false
        private set
    var remoteApproved: Boolean = false
        private set

    /** The local user's approval: the only thing that can make a ceremony committable (law L1). */
    fun localApprove() { localApproved = true }

    /** The peer's approval, learned from a message. It cannot commit anything by itself. */
    fun remoteApprove() { remoteApproved = true }
}

enum class RegistryRefusal {
    LOCAL_APPROVAL_REQUIRED, REMOTE_APPROVAL_REQUIRED, PAIR_EXISTS, REVOKED_CANNOT_REPAIR, NOT_FOUND, NOT_PAIRED, NOT_SUSPENDED, NOT_REVOKED,
    CLASS_OTHER_NOT_IN_MESH1, BAD_TRANSCRIPT, STORE_UNREADABLE, STORE_WRITE_FAILED, CORRUPT_ROW, NETWORK_TRANSITION_RETIRED, REVOKED_ROW,
}

class StatusChange(val pin: Pin, val from: PeerStatus?, val to: PeerStatus?, val goaway: GoawayReason?)

sealed interface RegistryResult {
    class Changed(val change: StatusChange) : RegistryResult
    data object Updated : RegistryResult
    class Refused(val code: RegistryRefusal) : RegistryResult
}

/** What a network message can attempt. In mesh-1 none of them changes a row (the hint frames are retired); a notice is only noted. */
sealed interface NetworkEvent {
    class RevocationHint(val from: Pin, val target: Pin) : NetworkEvent
    class RevokeNotice(val from: Pin) : NetworkEvent
    class StatusClaim(val pin: Pin, val claimed: PeerStatus) : NetworkEvent
    class PairMessage(val pin: Pin) : NetworkEvent
}

enum class DenyReason { NO_ROW, UNREADABLE, UNKNOWN_STATUS, NOT_PAIRED, UNREADABLE_SCOPES, UNKNOWN_SCOPE, SCOPE_NOT_GRANTED, ROUTING_DISABLED, UNKNOWN_CEILING }

sealed interface AuthDecision {
    data object Allow : AuthDecision
    class Deny(val reason: DenyReason) : AuthDecision
}

sealed interface OutboundDecision {
    class Allow(val ceiling: RouteCeiling) : OutboundDecision
    class Deny(val reason: DenyReason) : OutboundDecision
}

sealed interface SessionDecision {
    data object Keep : SessionDecision
    class Goaway(val reason: GoawayReason) : SessionDecision
}

fun interface RegistryListener {
    fun onChange(change: StatusChange)
}

/**
 * The peer registry of trust.md 4.7 as a state machine over a [PeerStore]. Only the listed transitions exist; every local one needs a
 * local action (a method call here; a network event can reach [onNetworkEvent] only, which never changes a row). Everything unreadable,
 * absent, corrupt or unrecognised denies. A change is durable before any listener hears of it.
 *
 * Every mutation is a read-modify-write of one stored row, so mutations are serialised by one lock held across the read, the write and the
 * listener calls (the lock is reentrant; a listener may call back). A revoke can therefore never land between another mutation's read and its
 * write and be overwritten (trust.md 15 L3). Reads ([authorize], [statusLookup], ...) take no lock and read the store afresh.
 */
class PeerRegistry(private val store: PeerStore) {
    private val listeners = CopyOnWriteArrayList<RegistryListener>()
    private val mutation = ReentrantLock()

    fun addListener(l: RegistryListener) { listeners += l }

    private sealed interface Loaded {
        class Row(val stored: StoredRow, val status: PeerStatus) : Loaded
        data object Absent : Loaded
        data object Unreadable : Loaded
        class Corrupt(val raw: Int) : Loaded
    }

    private fun load(pin: Pin): Loaded = try {
        when (val r = store.read(pin)) {
            StoreRead.Absent -> Loaded.Absent
            StoreRead.Unreadable -> Loaded.Unreadable
            is StoreRead.Present -> StatusCodes.decode(r.row.status)?.let { Loaded.Row(r.row, it) } ?: Loaded.Corrupt(r.row.status)
        }
    } catch (e: Exception) {
        Loaded.Unreadable
    }

    private fun refuse(c: RegistryRefusal) = RegistryResult.Refused(c)

    private fun notify(c: StatusChange) = listeners.forEach { it.onChange(c) }

    private fun persist(row: StoredRow): Boolean = try {
        store.write(row)
    } catch (e: Exception) {
        false
    }

    fun statusLookup(pin: Pin): StatusLookup = when (val l = load(pin)) {
        Loaded.Absent -> StatusLookup.Absent
        Loaded.Unreadable -> StatusLookup.Unreadable
        is Loaded.Corrupt -> StatusLookup.Corrupt(l.raw)
        is Loaded.Row -> StatusLookup.Known(l.status)
    }

    fun asStatusSource(): PinStatusSource = PinStatusSource { statusLookup(it) }

    // ---------------------------------------------------------------- the registry state machine (trust.md 4.7)

    /** (absent) to PAIRED: a completed ceremony with the LOCAL user's approval and the peer's. A revoked pin cannot re-pair until it is forgotten. */
    fun commitPairing(c: PairingCeremony, nowMs: Long): RegistryResult = mutation.withLock { commitPairingLocked(c, nowMs) }

    private fun commitPairingLocked(c: PairingCeremony, nowMs: Long): RegistryResult {
        if (!c.localApproved) return refuse(RegistryRefusal.LOCAL_APPROVAL_REQUIRED)
        if (!c.remoteApproved) return refuse(RegistryRefusal.REMOTE_APPROVAL_REQUIRED)
        if (c.clazz != PeerClass.OWN) return refuse(RegistryRefusal.CLASS_OTHER_NOT_IN_MESH1)
        if (c.transcript.size != 32) return refuse(RegistryRefusal.BAD_TRANSCRIPT)
        when (val l = load(c.pin)) {
            Loaded.Absent -> Unit
            Loaded.Unreadable -> return refuse(RegistryRefusal.STORE_UNREADABLE)
            is Loaded.Corrupt -> return refuse(RegistryRefusal.CORRUPT_ROW)
            is Loaded.Row -> return refuse(if (l.status == PeerStatus.REVOKED) RegistryRefusal.REVOKED_CANNOT_REPAIR else RegistryRefusal.PAIR_EXISTS)
        }
        val row = StoredRow(
            c.pin, c.label, c.platform, c.clazz.wire, StatusCodes.PAIRED, encodeScopes(c.grantedInbound), 0, RouteCeiling.D1.name,
            c.transcript.copyOf(), false, "user", nowMs,
        )
        if (!persist(row)) return refuse(RegistryRefusal.STORE_WRITE_FAILED)
        val change = StatusChange(c.pin, null, PeerStatus.PAIRED, null)
        notify(change)
        return RegistryResult.Changed(change)
    }

    private fun transition(pin: Pin, from: Set<PeerStatus>, to: PeerStatus, wrong: RegistryRefusal, goaway: GoawayReason?, reason: String, nowMs: Long): RegistryResult =
        mutation.withLock { transitionLocked(pin, from, to, wrong, goaway, reason, nowMs) }

    private fun transitionLocked(pin: Pin, from: Set<PeerStatus>, to: PeerStatus, wrong: RegistryRefusal, goaway: GoawayReason?, reason: String, nowMs: Long): RegistryResult {
        val l = load(pin)
        val row = when (l) {
            Loaded.Absent -> return refuse(RegistryRefusal.NOT_FOUND)
            Loaded.Unreadable -> return refuse(RegistryRefusal.STORE_UNREADABLE)
            is Loaded.Corrupt -> return refuse(RegistryRefusal.CORRUPT_ROW)
            is Loaded.Row -> l
        }
        if (row.status !in from) return refuse(wrong)
        if (!persist(row.stored.copy(status = StatusCodes.encode(to), statusReason = reason, statusChangedAt = nowMs))) return refuse(RegistryRefusal.STORE_WRITE_FAILED)
        val change = StatusChange(pin, row.status, to, if (row.status == PeerStatus.PAIRED) goaway else null)
        notify(change)
        return RegistryResult.Changed(change)
    }

    /** PAIRED to SUSPENDED: the user taps Pause. */
    fun pause(pin: Pin, nowMs: Long): RegistryResult = transition(pin, setOf(PeerStatus.PAIRED), PeerStatus.SUSPENDED, RegistryRefusal.NOT_PAIRED, GoawayReason.SUSPENDED, "user", nowMs)

    /** SUSPENDED to PAIRED: only the user's Restore. */
    fun restore(pin: Pin, nowMs: Long): RegistryResult = transition(pin, setOf(PeerStatus.SUSPENDED), PeerStatus.PAIRED, RegistryRefusal.NOT_SUSPENDED, null, "user-restore", nowMs)

    /** PAIRED or SUSPENDED to REVOKED: the user taps Revoke. */
    fun revoke(pin: Pin, nowMs: Long): RegistryResult =
        transition(pin, setOf(PeerStatus.PAIRED, PeerStatus.SUSPENDED), PeerStatus.REVOKED, RegistryRefusal.REVOKED_ROW, GoawayReason.REVOKED, "user", nowMs)

    /** REVOKED to absent: only the user's Forget, and only for a revoked row. */
    fun forget(pin: Pin): RegistryResult = mutation.withLock { forgetLocked(pin) }

    private fun forgetLocked(pin: Pin): RegistryResult {
        val row = when (val l = load(pin)) {
            Loaded.Absent -> return refuse(RegistryRefusal.NOT_FOUND)
            Loaded.Unreadable -> return refuse(RegistryRefusal.STORE_UNREADABLE)
            is Loaded.Corrupt -> return refuse(RegistryRefusal.CORRUPT_ROW)
            is Loaded.Row -> l
        }
        if (row.status != PeerStatus.REVOKED) return refuse(RegistryRefusal.NOT_REVOKED)
        val ok = try { store.delete(pin) } catch (e: Exception) { false }
        if (!ok) return refuse(RegistryRefusal.STORE_WRITE_FAILED)
        val change = StatusChange(pin, PeerStatus.REVOKED, null, null)
        notify(change)
        return RegistryResult.Changed(change)
    }

    /** What this peer may ask of me. Does not touch the status or the outbound controls. */
    fun setInboundScopes(pin: Pin, scopes: Set<Scope>): RegistryResult = update(pin) { it.copy(inboundScopesJson = encodeScopes(scopes)) }

    /** What I do toward this peer. Does not touch the status or the inbound scopes. */
    fun setRoute(pin: Pin, enabled: Boolean, ceiling: RouteCeiling): RegistryResult = update(pin) { it.copy(routeEnabled = if (enabled) 1 else 0, routeCeiling = ceiling.name) }

    private fun update(pin: Pin, f: (StoredRow) -> StoredRow): RegistryResult = mutation.withLock { updateLocked(pin, f) }

    private fun updateLocked(pin: Pin, f: (StoredRow) -> StoredRow): RegistryResult {
        val row = when (val l = load(pin)) {
            Loaded.Absent -> return refuse(RegistryRefusal.NOT_FOUND)
            Loaded.Unreadable -> return refuse(RegistryRefusal.STORE_UNREADABLE)
            is Loaded.Corrupt -> return refuse(RegistryRefusal.CORRUPT_ROW)
            is Loaded.Row -> l
        }
        if (row.status == PeerStatus.REVOKED) return refuse(RegistryRefusal.REVOKED_ROW)
        return if (persist(f(row.stored))) RegistryResult.Updated else refuse(RegistryRefusal.STORE_WRITE_FAILED)
    }

    /** No message reaches PAIRED or any other status (R3). A revoke notice is only noted: it cannot change trust in either direction. */
    fun onNetworkEvent(e: NetworkEvent): RegistryResult = when (e) {
        is NetworkEvent.RevokeNotice, is NetworkEvent.PairMessage -> RegistryResult.Updated
        is NetworkEvent.RevocationHint, is NetworkEvent.StatusClaim -> refuse(RegistryRefusal.NETWORK_TRANSITION_RETIRED)
    }

    // ---------------------------------------------------------------- reads, per frame

    /** `authorize(pin, scope) := row != null && row.status == PAIRED && scope in row.inboundScopes`; everything else, including an unrecognised status, denies. Read afresh on every call. */
    fun authorize(pin: Pin, scope: String): AuthDecision {
        val row = when (val l = load(pin)) {
            Loaded.Absent -> return AuthDecision.Deny(DenyReason.NO_ROW)
            Loaded.Unreadable -> return AuthDecision.Deny(DenyReason.UNREADABLE)
            is Loaded.Corrupt -> return AuthDecision.Deny(DenyReason.UNKNOWN_STATUS)
            is Loaded.Row -> l
        }
        if (row.status != PeerStatus.PAIRED) return AuthDecision.Deny(DenyReason.NOT_PAIRED)
        val granted = decodeScopes(row.stored.inboundScopesJson) ?: return AuthDecision.Deny(DenyReason.UNREADABLE_SCOPES)
        val s = Scope.of(scope) ?: return AuthDecision.Deny(DenyReason.UNKNOWN_SCOPE)
        return if (s in granted) AuthDecision.Allow else AuthDecision.Deny(DenyReason.SCOPE_NOT_GRANTED)
    }

    /** May my router place work on this peer (`routeEnabled`) and up to which data class. */
    fun authorizeOutbound(pin: Pin): OutboundDecision {
        val row = when (val l = load(pin)) {
            Loaded.Absent -> return OutboundDecision.Deny(DenyReason.NO_ROW)
            Loaded.Unreadable -> return OutboundDecision.Deny(DenyReason.UNREADABLE)
            is Loaded.Corrupt -> return OutboundDecision.Deny(DenyReason.UNKNOWN_STATUS)
            is Loaded.Row -> l
        }
        if (row.status != PeerStatus.PAIRED) return OutboundDecision.Deny(DenyReason.NOT_PAIRED)
        if (row.stored.routeEnabled != 1) return OutboundDecision.Deny(DenyReason.ROUTING_DISABLED)
        val ceiling = RouteCeiling.entries.firstOrNull { it.name == row.stored.routeCeiling } ?: return OutboundDecision.Deny(DenyReason.UNKNOWN_CEILING)
        return OutboundDecision.Allow(ceiling)
    }

    /** The scopes a `HELLO_ACK` may list as granted: those of a clean PAIRED row, in the fixed order infer, manifest, state. */
    fun granted(pin: Pin): List<Scope> {
        val row = load(pin) as? Loaded.Row ?: return emptyList()
        if (row.status != PeerStatus.PAIRED) return emptyList()
        val g = decodeScopes(row.stored.inboundScopesJson) ?: return emptyList()
        return Scope.entries.filter { it in g }
    }

    /**
     * What to do with an open session of this peer, re-evaluated from the current row: a row that left PAIRED closes its sessions with
     * `revoked` or `suspended`. An absent row says `revoked`; an unreadable or corrupt row says `suspended` (closed, nothing destroyed).
     * The decision is returned; sending the GOAWAY is the host's job.
     */
    fun sessionDecision(pin: Pin): SessionDecision = when (val l = load(pin)) {
        Loaded.Absent -> SessionDecision.Goaway(GoawayReason.REVOKED)
        Loaded.Unreadable, is Loaded.Corrupt -> SessionDecision.Goaway(GoawayReason.SUSPENDED)
        is Loaded.Row -> when (l.status) {
            PeerStatus.PAIRED -> SessionDecision.Keep
            PeerStatus.SUSPENDED -> SessionDecision.Goaway(GoawayReason.SUSPENDED)
            PeerStatus.REVOKED -> SessionDecision.Goaway(GoawayReason.REVOKED)
        }
    }

    companion object {
        fun encodeScopes(s: Set<Scope>): String = Jcs.serializeToString(JArray(Scope.entries.filter { it in s }.map { JString(it.wire) }))

        /** The scopes of a JSON array text, or null for anything that is not a strict array of known scope names without repeats. */
        fun decodeScopes(text: String): Set<Scope>? {
            val r = StrictJson.parse(text.toByteArray(Charsets.UTF_8)) as? ParseResult.Ok ?: return null
            val a = r.value as? JArray ?: return null
            val out = LinkedHashSet<Scope>()
            for (item in a.items) {
                val s = Scope.of((item as? JString)?.value ?: return null) ?: return null
                if (!out.add(s)) return null
            }
            return out
        }
    }
}
