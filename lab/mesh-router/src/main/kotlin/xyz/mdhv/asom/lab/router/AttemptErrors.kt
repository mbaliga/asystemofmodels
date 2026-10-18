package xyz.mdhv.asom.lab.router

/**
 * The error of a request whose planned attempts all ended without serving it. [code] is null when no existing code is true of the situation: that is an owner
 * decision (router.md 5.6 names `NO_ELIGIBLE_NODE`, which r3 removed), and nothing invents a code in its place. [why] is the lab's own label for the situation.
 */
data class AttemptsError(val code: String?, val why: String)

object AttemptErrors {
    const val ALL_ATTEMPTS_FAILED = "all-attempts-failed"
    const val DEADLINE = "deadline"

    /**
     * [failed] holds the indexes of the plan's attempts that were tried and ended without serving; [deadlineHit] says the request ran out of time with attempts left.
     * `ALL_PROVIDERS_COOLING` is true exactly when the plan has cloud attempts and every one of them failed (each failure puts the provider into the v1 cooldown);
     * every other situation has no code in the frozen v1 set or [MeshErrorCodes] (ERR-FX4-RT-6).
     */
    fun errorAfterAttempts(plan: List<PlannedAttempt>, failed: Set<Int>, deadlineHit: Boolean): AttemptsError {
        val cloud = plan.indices.filter { plan[it].tier == Tier.CLOUD }
        if (cloud.isNotEmpty() && failed.containsAll(cloud)) return AttemptsError("ALL_PROVIDERS_COOLING", ALL_ATTEMPTS_FAILED)
        return AttemptsError(null, if (deadlineHit) DEADLINE else ALL_ATTEMPTS_FAILED)
    }
}
