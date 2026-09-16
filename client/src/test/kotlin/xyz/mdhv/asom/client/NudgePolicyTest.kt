package xyz.mdhv.asom.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §10A.5 anti-spam laws, unit-tested (pure JVM logic). */
class NudgePolicyTest {

    private val policy = NudgePolicy()
    private val day = 24L * 60 * 60 * 1000

    private fun signals(
        asomInstalled: Boolean = false,
        suiteAppCount: Int = 0,
        holdsLocal: Boolean = false,
        bytes: Long = 5_000_000_000,
    ) = NudgePolicy.Signals(
        asomInstalled = asomInstalled,
        asomPaired = false,
        suiteAppCount = suiteAppCount,
        reclaimableBytes = bytes,
        appLabels = listOf("FoneBru"),
        holdsLocalKeyOrModel = holdsLocal,
    )

    @Test
    fun `single-app user is NEVER nagged`() {
        val d = policy.decide(signals(suiteAppCount = 0), NudgePolicy.State(), nowMs = 0)
        assertEquals(NudgePolicy.Decision.None, d)
    }

    @Test
    fun `sibling detected and no asom yields quantified install pitch`() {
        val d = policy.decide(signals(suiteAppCount = 1), NudgePolicy.State(), nowMs = 0)
        val install = d as NudgePolicy.Decision.SuggestInstall
        assertEquals(2, install.suiteAppCount) // sibling + this app
        assertEquals(5_000_000_000, install.reclaimableBytes)
        assertTrue(install.appLabels.isNotEmpty())
    }

    @Test
    fun `asom present and local key held yields handoff offer`() {
        val d = policy.decide(
            signals(asomInstalled = true, holdsLocal = true),
            NudgePolicy.State(), nowMs = 0,
        )
        assertTrue(d is NudgePolicy.Decision.SuggestHandoff)
    }

    @Test
    fun `asom present and nothing held locally is silent`() {
        val d = policy.decide(signals(asomInstalled = true, suiteAppCount = 3), NudgePolicy.State(), nowMs = 0)
        assertEquals(NudgePolicy.Decision.None, d)
    }

    @Test
    fun `dismiss imposes a long cooldown`() {
        var state = NudgePolicy.State()
        state = policy.onDismissed(state, nowMs = 0)
        // 29 days later: still silent.
        assertEquals(
            NudgePolicy.Decision.None,
            policy.decide(signals(suiteAppCount = 2), state, nowMs = 29 * day),
        )
        // 31 days later: allowed again.
        assertTrue(
            policy.decide(signals(suiteAppCount = 2), state, nowMs = 31 * day)
                is NudgePolicy.Decision.SuggestInstall,
        )
    }

    @Test
    fun `lifetime cap silences forever`() {
        var state = NudgePolicy.State()
        repeat(NudgePolicy.DEFAULT_LIFETIME_CAP) { state = policy.onShown(state) }
        assertEquals(
            NudgePolicy.Decision.None,
            policy.decide(signals(suiteAppCount = 5), state, nowMs = Long.MAX_VALUE / 2),
        )
    }
}
