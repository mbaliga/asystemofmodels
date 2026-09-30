package xyz.mdhv.asom.lab.router

import xyz.mdhv.asom.contract.AsomException
import xyz.mdhv.asom.contract.Policy
import xyz.mdhv.asom.routing.Candidate
import xyz.mdhv.asom.routing.CooldownRegistry
import xyz.mdhv.asom.routing.LatencyTracker
import xyz.mdhv.asom.routing.RouteQuery
import xyz.mdhv.asom.routing.Router

sealed interface CloudTier {
    class Plan(val candidates: List<Candidate>) : CloudTier

    /** A typed `AsomException` becomes an empty tier plus the remembered code (LAB_SPEC 6.7). */
    class Failed(val code: String) : CloudTier
}

/**
 * The frozen adapters of LAB_SPEC 6.7: a throwaway v1 `Router` built from the snapshot's frozen inputs and called UNMODIFIED. Cooldown deadlines are
 * replayed through a registry whose clock is the snapshot's wall time, so `Router.plan` sees exactly the cooling set the requester froze. R04 pins this
 * adapter and law RL1 compares it with every R04 vector.
 */
object CloudAdapter {
    private const val V1_BASE_COOLDOWN_MS = 30_000L

    fun plan(frozen: FrozenCloudView, q: RouteQuery, wallNowMs: Long, defaultPolicy: Policy): CloudTier {
        var t = wallNowMs
        val cooldowns = CooldownRegistry(clock = { t })
        for ((id, until) in frozen.coolingUntilWallMs.toSortedMap()) {
            if (until > wallNowMs) {
                t = until - V1_BASE_COOLDOWN_MS
                cooldowns.recordFailure(id)
            }
        }
        t = wallNowMs
        val latency = LatencyTracker().apply { preload(frozen.ewmaMs) }
        val router = Router(
            catalogue = { frozen.catalogue },
            keys = { id -> id in frozen.keysPresent },
            latency = latency,
            cooldowns = cooldowns,
            defaultPolicy = defaultPolicy,
            hasLocalEngine = false,
        )
        return try {
            CloudTier.Plan(router.plan(q))
        } catch (e: AsomException) {
            CloudTier.Failed(e.code.name)
        }
    }
}
