package xyz.mdhv.asom.lab.policy

/** `T` this device's engine, `O` an own paired peer's engine, `C` a cloud provider under the user's key (LAB_SPEC 6.2; `X`, the other-owner peer, is out of the lattice). */
enum class Dest { T, O, C }

/**
 * Every input that shapes the permitted destination set `P` for one request (LAB_SPEC 6.2, contract.md 5.2 without the `X` rows).
 * `fallbackProviders` is null when `X-Asom-Fallback` is absent; `X-Asom-No-Train` never changes `P` (it filters inside `C`, as in v1).
 */
data class PolicyInputs(
    val meshGlobalOn: Boolean,
    val appMeshAllowed: Boolean,
    val appCloudBanned: Boolean,
    val appDeviceOnly: Boolean,
    val policyLocalOnly: Boolean,
    val fallbackProviders: List<String>?,
    val noTrain: Boolean = false,
)

/** `P`, sorted `T < O < C`, plus the provider list `C` is restricted to when the fallback header named one. */
data class DestinationSet(val dests: Set<Dest>, val cloudRestrictedTo: List<String>?) {
    val wire: List<String> get() = dests.sorted().map { it.name }
}

object DestinationSets {
    /**
     * Start from `{T, O, C}` and intersect with every applicable rule; the most restrictive rule always wins and no rule can add a destination
     * another removed. An `X-Asom-Fallback` header that names no provider leaves no cloud provider to try, so `P` is empty (ERRATA ERR-LP-1).
     */
    fun compute(i: PolicyInputs): DestinationSet {
        var p = setOf(Dest.T, Dest.O, Dest.C)
        if (!i.meshGlobalOn) p = p - Dest.O
        if (!i.appMeshAllowed) p = p - Dest.O
        if (i.appCloudBanned) p = p - Dest.C
        if (i.appDeviceOnly) p = p intersect setOf(Dest.T)
        if (i.policyLocalOnly) p = p intersect setOf(Dest.T)
        var restricted: List<String>? = null
        val fb = i.fallbackProviders
        if (fb != null) {
            p = p intersect setOf(Dest.C)
            if (fb.isEmpty()) p = emptySet() else if (Dest.C in p) restricted = fb.toList()
        }
        return DestinationSet(p.toSortedSet(), restricted)
    }

    /** The named classes of contract.md 5.2, derived from `P` and never stored separately; null for any other set. */
    fun namedClass(p: Set<Dest>): String? = when (p.toSortedSet().toList()) {
        listOf(Dest.T) -> "device-only"
        listOf(Dest.T, Dest.O) -> "own-devices"
        listOf(Dest.T, Dest.O, Dest.C) -> "own-and-cloud"
        listOf(Dest.T, Dest.C) -> "cloud-no-peers"
        else -> null
    }

    /**
     * The r3 defaults (design 8.5, changed from contract.md by X24): every app defaults to mesh OFF. When the user switches "use my devices"
     * on, existing apps appear with an UNTICKED box; apps paired later default to off; cloud-banned apps stay off unless set individually.
     */
    fun defaultAppMeshAllowed(pairedBeforeSwitch: Boolean, cloudBanned: Boolean, userTicked: Boolean?): Boolean = userTicked ?: false
}

enum class PeerStatus { PAIRED, SUSPENDED, REVOKED }

/** What the requester's own registry says about a peer (never taken from the peer). */
data class PeerRegistryView(val status: PeerStatus, val routeEnabled: Boolean, val inferGrantedToMe: Boolean)

sealed interface Eligibility {
    data object Eligible : Eligibility

    /** The first failing row of F1..F3 (LAB_SPEC 6.3) is recorded. */
    data class Excluded(val code: String) : Eligibility
}

object PeerEligibility {
    /**
     * The hard filters that depend on policy and the registry only (RL12: no manifest or live-state value can change them): `F1_ELIGIBILITY` (`O` not in `P`,
     * or the peer row denies), `F2_NOT_PAIRED`, `F3_NO_SCOPE`. Checked again immediately before each intent row and before `INFER_BODY`.
     */
    fun evaluate(p: Set<Dest>, peer: PeerRegistryView): Eligibility = when {
        Dest.O !in p || !peer.routeEnabled -> Eligibility.Excluded("F1_ELIGIBILITY")
        peer.status != PeerStatus.PAIRED -> Eligibility.Excluded("F2_NOT_PAIRED")
        !peer.inferGrantedToMe -> Eligibility.Excluded("F3_NO_SCOPE")
        else -> Eligibility.Eligible
    }
}

enum class Role { REQUESTER, LENDER }

/** The four conditions of the quiescence law (design 8.6) as facts about this node right now. */
data class QuiescenceInputs(
    val role: Role,
    val localRequestPendingWithPeerInP: Boolean,
    val peerStatusScreenOpen: Boolean,
    val userStartedPeerOperation: Boolean,
    val inflightAttemptFinishing: Boolean,
)

object Quiescence {
    /**
     * A node initiates a peer connection only when one of the four conditions holds. In its LENDING role a node initiates none except under rule 3
     * (a user revoking a peer, sharing a report or pairing on a lender): a lender's own pending local request or open screen does not make it dial.
     */
    fun mayInitiate(i: QuiescenceInputs): Boolean = when (i.role) {
        Role.LENDER -> i.userStartedPeerOperation
        Role.REQUESTER -> i.localRequestPendingWithPeerInP || i.peerStatusScreenOpen || i.userStartedPeerOperation || i.inflightAttemptFinishing
    }

    /** A lender accepts inbound connections only while SERVING, or while a pairing window or the Peers tab is open. */
    fun mayAcceptInbound(fsm: Fsm, pairingWindowOpen: Boolean, peersTabOpen: Boolean): Boolean = fsm == Fsm.SERVING || pairingWindowOpen || peersTabOpen

    /** Sessions close after 5 minutes without an open stream and after 30 minutes of age (design 8.6). */
    fun sessionShouldClose(idleMs: Long, ageMs: Long, openStreams: Int): Boolean = (openStreams == 0 && idleMs >= 300_000) || ageMs >= 1_800_000
}
