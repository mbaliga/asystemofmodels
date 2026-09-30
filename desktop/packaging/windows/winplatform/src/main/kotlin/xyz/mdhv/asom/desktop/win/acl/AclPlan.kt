package xyz.mdhv.asom.desktop.win.acl

/** Well-known SIDs. Names are locale-dependent ("Administratoren"), SIDs are not, so every rule here is by SID. */
object WellKnownSid {
    const val SYSTEM = "S-1-5-18"
    const val ADMINISTRATORS = "S-1-5-32-544"
    const val USERS = "S-1-5-32-545"
    const val EVERYONE = "S-1-1-0"
    const val AUTHENTICATED_USERS = "S-1-5-11"
    const val CREATOR_OWNER = "S-1-3-0"

    /** Principals that stand for "any local user": an allow ACE for one of them defeats an owner-only DACL. */
    val BROAD: Set<String> = setOf(EVERYONE, AUTHENTICATED_USERS, USERS, "S-1-5-4", "S-1-5-2", "S-1-2-0", "S-1-5-32-546")
}

/** Rights as the node reasons about them; `FULL` implies both others. The real mapping from NTFS masks is [AclRights]. */
enum class AclRight { READ, WRITE, FULL }

data class Ace(val sid: String, val allow: Boolean, val rights: Set<AclRight>, val inherit: Boolean = true)

/** What a read-back of a DACL yields, reduced to SIDs and rights. */
data class AclSnapshot(val ownerSid: String?, val aces: List<Ace>)

/**
 * The owner-only DACL a directory or file must carry, and the check that a read-back honours it. The plan says who may
 * have any access ([allowedSids]) and who must have what ([required]). A DACL that grants anything to another principal, and
 * above all to a broad group that `%ProgramData%` inherits (Users, Authenticated Users), fails.
 *
 * What this does NOT prove: that an Administrator or SYSTEM cannot read the file (they always can), and nothing about
 * later changes (the check is a moment, and TOCTOU-prone when the caller acts on it afterwards).
 */
class AclPlan(
    val label: String,
    val required: List<Ace>,
    val allowedOwners: Set<String>,
) {
    val allowedSids: Set<String> = required.filter { it.allow }.map { it.sid }.toSet()

    init {
        require(required.isNotEmpty()) { "a plan without an owner is not a plan" }
        require(required.none { it.sid in WellKnownSid.BROAD || it.sid == WellKnownSid.CREATOR_OWNER }) { "a plan must never grant a broad or placeholder principal" }
    }

    companion object {
        private val full = setOf(AclRight.FULL)

        /** User mode: the owner, SYSTEM and Administrators (who can take ownership anyway). */
        fun userState(ownerSid: String): AclPlan = AclPlan(
            "user-state",
            listOf(Ace(ownerSid, true, full), Ace(WellKnownSid.SYSTEM, true, full), Ace(WellKnownSid.ADMINISTRATORS, true, full)),
            setOf(ownerSid, WellKnownSid.ADMINISTRATORS, WellKnownSid.SYSTEM),
        )

        /** Service mode: SYSTEM, Administrators and the service SID only. The owner has no access to the state or the key file. */
        fun serviceState(serviceSid: String): AclPlan = AclPlan(
            "service-state",
            listOf(Ace(serviceSid, true, full), Ace(WellKnownSid.SYSTEM, true, full), Ace(WellKnownSid.ADMINISTRATORS, true, full)),
            setOf(serviceSid, WellKnownSid.ADMINISTRATORS, WellKnownSid.SYSTEM),
        )

        /** Service mode `run\`: the owner may write (connecting to an AF_UNIX socket needs write on the socket file [FW33]) and nothing else. */
        fun serviceRunDir(ownerSid: String, serviceSid: String): AclPlan = AclPlan(
            "service-run",
            listOf(
                Ace(serviceSid, true, full), Ace(WellKnownSid.SYSTEM, true, full), Ace(WellKnownSid.ADMINISTRATORS, true, full),
                Ace(ownerSid, true, setOf(AclRight.WRITE, AclRight.READ)),
            ),
            setOf(serviceSid, WellKnownSid.ADMINISTRATORS, WellKnownSid.SYSTEM),
        )

        fun userRunDir(ownerSid: String): AclPlan = userState(ownerSid).let { AclPlan("user-run", it.required, it.allowedOwners) }
    }
}

object AclVerifier {
    /** Empty means the snapshot honours the plan. Every message names a SID, never a locale-dependent account name. */
    fun violations(plan: AclPlan, snap: AclSnapshot): List<String> {
        val out = ArrayList<String>()
        val owner = snap.ownerSid
        if (owner == null) out += "owner unreadable" else if (owner !in plan.allowedOwners) out += "owner is $owner, expected one of ${plan.allowedOwners.sorted()}"
        for (a in snap.aces) {
            if (!a.allow || a.rights.isEmpty()) continue
            if (a.sid !in plan.allowedSids) {
                out += "allow ACE for ${a.sid} (${a.rights.sorted()}) is not in the plan '${plan.label}'"
                continue
            }
            val planned = plan.required.filter { it.sid == a.sid }.flatMap { it.rights }.toSet()
            if (AclRight.FULL !in planned && !planned.containsAll(a.rights)) {
                out += "allow ACE for ${a.sid} grants ${a.rights.sorted()}, more than the plan '${plan.label}' allows (${planned.sorted()})"
            }
        }
        for (need in plan.required) {
            val held = snap.aces.filter { it.allow && it.sid == need.sid }.flatMap { it.rights }.toSet()
            val ok = AclRight.FULL in held || need.rights.all { it in held }
            if (!ok) out += "required ACE for ${need.sid} (${need.rights.sorted()}) is missing"
        }
        return out
    }
}
