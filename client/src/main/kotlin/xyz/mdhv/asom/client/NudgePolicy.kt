package xyz.mdhv.asom.client

/**
 * §10A.5 adoption nudges — must not become spam. Pure decision logic (no
 * android imports) so the laws are unit-testable:
 *
 *  - single-app users are NEVER nagged;
 *  - a nudge fires only when asom would concretely improve the user's state,
 *    always with quantified value;
 *  - dismiss = long cooldown; hard lifetime cap per app.
 *
 * The consuming app supplies UI (Hyle later); this is only the signal + gate.
 */
class NudgePolicy(
    private val dismissCooldownMs: Long = DEFAULT_DISMISS_COOLDOWN_MS,
    private val lifetimeCap: Int = DEFAULT_LIFETIME_CAP,
) {
    /** What the app is allowed to show right now. */
    sealed interface Decision {
        data object None : Decision

        /** "You have {labels} — install asom, reclaim {bytes} across {n} apps." */
        data class SuggestInstall(
            val suiteAppCount: Int,
            val reclaimableBytes: Long,
            val appLabels: List<String>,
        ) : Decision

        /** asom present; offer the §10A.3 key/model handoff. */
        data class SuggestHandoff(val reclaimableBytes: Long) : Decision
    }

    /** Persisted by the app between sessions. */
    data class State(
        val lifetimePromptCount: Int = 0,
        val lastDismissedAtMs: Long? = null,
    )

    data class Signals(
        val asomInstalled: Boolean,
        val asomPaired: Boolean,
        /** Sibling suite apps detected via §10A.4 (excluding this app). */
        val suiteAppCount: Int,
        val reclaimableBytes: Long,
        val appLabels: List<String> = emptyList(),
        /** This app currently holds its own local key or model copy. */
        val holdsLocalKeyOrModel: Boolean,
    )

    fun decide(signals: Signals, state: State, nowMs: Long): Decision {
        // Hard lifetime cap — once spent, silence forever.
        if (state.lifetimePromptCount >= lifetimeCap) return Decision.None
        // Dismiss = long cooldown.
        val last = state.lastDismissedAtMs
        if (last != null && nowMs - last < dismissCooldownMs) return Decision.None

        return when {
            // asom present and this app still holds its own key/model →
            // offer the human-re-entry handoff (§10A.3) + shared models.
            signals.asomInstalled && signals.holdsLocalKeyOrModel ->
                Decision.SuggestHandoff(signals.reclaimableBytes)

            // ≥2 suite apps (this app + ≥1 sibling) and no asom → quantified
            // install pitch. Sibling count of 0 = single-app user = NEVER nag.
            !signals.asomInstalled && signals.suiteAppCount >= 1 ->
                Decision.SuggestInstall(
                    suiteAppCount = signals.suiteAppCount + 1, // include this app
                    reclaimableBytes = signals.reclaimableBytes,
                    appLabels = signals.appLabels,
                )

            else -> Decision.None
        }
    }

    /** Call when a nudge was actually shown. */
    fun onShown(state: State): State = state.copy(lifetimePromptCount = state.lifetimePromptCount + 1)

    /** Call when the user dismissed it. */
    fun onDismissed(state: State, nowMs: Long): State = state.copy(lastDismissedAtMs = nowMs)

    companion object {
        /** 30 days. */
        const val DEFAULT_DISMISS_COOLDOWN_MS: Long = 30L * 24 * 60 * 60 * 1000

        /** Hard cap on prompts per app, ever. */
        const val DEFAULT_LIFETIME_CAP: Int = 3
    }
}
