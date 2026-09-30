package xyz.mdhv.asom.ut

import xyz.mdhv.asom.desktop.MonotonicClock

/** The states of ubuntu-touch.md 3.3. `FROZEN` is a model state: a stopped process cannot observe it, so it is left by SIGCONT-detection only. */
enum class NodeState { STARTING, IDLE, ACTIVE, FREEZING, FROZEN, RESUMING, LEDGER_FAIL }

sealed interface LcEvent {
    data object LedgerReady : LcEvent
    data object LedgerFailed : LcEvent
    data class Ui(val state: UiLifecycle) : LcEvent
    data object PeersOpened : LcEvent
    data object PeersClosed : LcEvent
    data class RequestArrived(val rid: String) : LcEvent
    data object SessionOpened : LcEvent
    data object SessionClosed : LcEvent
    data class AttemptStarted(val attemptId: String) : LcEvent
    data class BodySent(val attemptId: String) : LcEvent
    data class AttemptFinished(val attemptId: String) : LcEvent
    data object Drained : LcEvent
    data object Resumed : LcEvent
    data object Tick : LcEvent
    data object Shutdown : LcEvent
}

/** Work the host must do. The FSM never touches a socket, a ledger or the display itself. */
sealed interface LcEffect {
    val label: String

    data object HoldDisplay : LcEffect { override val label = "HoldDisplay" }
    data object ReleaseDisplay : LcEffect { override val label = "ReleaseDisplay" }
    data object CancelAttempts : LcEffect { override val label = "CancelAttempts" }
    data class CloseSessions(val closedBy: String) : LcEffect { override val label = "CloseSessions($closedBy)" }
    data class WriteInterruptedRows(val attemptIds: List<String>) : LcEffect { override val label = "WriteInterruptedRows(${attemptIds.joinToString(",")})" }
    data object ForceLedger : LcEffect { override val label = "ForceLedger" }
    data object ExitNow : LcEffect { override val label = "ExitNow" }
}

class LcStep(val state: NodeState, val effects: List<LcEffect>, val refused: UiErrorCode? = null)

/**
 * The lifecycle FSM of the Ubuntu Touch node (ubuntu-touch.md 3.3) with laws L-UT1 to L-UT3. Pure: time comes from the
 * [MonotonicClock], work goes out as [LcEffect]s, and nothing is ever retried (a retry would put the prompt on a second
 * device without the owner asking, design 7.12). Not thread-safe; the session serialises access.
 *
 * Boundaries (ERRATA ERR-UT-FSM-2): the UI counts as active while its last `active` report is at most 10,000 ms old; the
 * watchdog fires on a gap strictly over 3,000 ms; the idle close fires at 300,000 ms of no work.
 */
class NodeLifecycle(private val clock: MonotonicClock) {
    var state: NodeState = NodeState.STARTING
        private set
    var sessions: Int = 0
        private set
    var displayHeld: Boolean = false
        private set
    var stopped: Boolean = false
        private set

    private val startedMs: Long = clock.nowMs()
    private var lastTickMs: Long = startedMs
    private var lastActiveMs: Long? = null
    private var uiState: UiLifecycle? = null
    private var lastWorkMs: Long = startedMs
    private val attempts = LinkedHashSet<String>()
    private val bodies = HashSet<String>()

    val openAttempts: List<String> get() = attempts.toList()

    /** L-UT1: a peer connection may be initiated only in ACTIVE, with the UI's last `active` at most 10 s old. */
    fun mayDial(): Boolean = !stopped && state == NodeState.ACTIVE && uiRecentlyActive(clock.nowMs())

    private fun uiRecentlyActive(now: Long): Boolean {
        val last = lastActiveMs ?: return false
        return uiState == UiLifecycle.ACTIVE && now - last <= ACTIVE_WINDOW_MS
    }

    fun wireState(): String = when (state) {
        NodeState.STARTING, NodeState.IDLE -> NodeStates.IDLE
        NodeState.ACTIVE -> NodeStates.ACTIVE
        NodeState.FREEZING, NodeState.FROZEN, NodeState.RESUMING, NodeState.LEDGER_FAIL -> NodeStates.INTERRUPTED
    }

    fun apply(event: LcEvent): LcStep {
        if (stopped) return LcStep(state, emptyList())
        val now = clock.nowMs()
        if (event is LcEvent.Ui) {
            uiState = event.state
            if (event.state == UiLifecycle.ACTIVE) lastActiveMs = now
        }
        return when (event) {
            LcEvent.LedgerReady -> if (state == NodeState.STARTING) moveTo(NodeState.IDLE) else same()
            LcEvent.LedgerFailed -> ledgerFail()
            is LcEvent.Ui -> onUi(event.state)
            LcEvent.PeersOpened -> permit(now)
            LcEvent.PeersClosed -> same()
            is LcEvent.RequestArrived -> permit(now)
            LcEvent.SessionOpened -> guarded(now) { sessions++ }
            LcEvent.SessionClosed -> { if (sessions > 0) sessions--; same() }
            is LcEvent.AttemptStarted -> guarded(now) { attempts += event.attemptId; lastWorkMs = now }
            is LcEvent.BodySent -> onBodySent(event.attemptId)
            is LcEvent.AttemptFinished -> onAttemptFinished(event.attemptId, now)
            LcEvent.Drained -> onDrained()
            LcEvent.Resumed -> if (state == NodeState.RESUMING) moveTo(NodeState.IDLE) else same()
            LcEvent.Tick -> onTick(now)
            LcEvent.Shutdown -> shutdown()
        }
    }

    private fun same(effects: List<LcEffect> = emptyList(), refused: UiErrorCode? = null) = LcStep(state, effects, refused)

    private fun moveTo(next: NodeState, effects: List<LcEffect> = emptyList()): LcStep {
        state = next
        return LcStep(state, effects)
    }

    private fun refusal(): UiErrorCode = when (state) {
        NodeState.STARTING, NodeState.LEDGER_FAIL -> UiErrorCode.LEDGER_UNAVAILABLE
        else -> UiErrorCode.INTERRUPTED_BY_SUSPEND
    }

    /** A local request, the Peers screen or a user peer operation: the only ways IDLE becomes ACTIVE. */
    private fun permit(now: Long): LcStep = when (state) {
        NodeState.IDLE, NodeState.ACTIVE ->
            if (!uiRecentlyActive(now)) same(refused = UiErrorCode.INTERRUPTED_BY_SUSPEND)
            else {
                lastWorkMs = now
                if (state == NodeState.IDLE) moveTo(NodeState.ACTIVE) else same()
            }
        else -> same(refused = refusal())
    }

    private inline fun guarded(now: Long, action: () -> Unit): LcStep =
        if (state == NodeState.ACTIVE && uiRecentlyActive(now)) {
            action()
            same()
        } else {
            same(refused = refusal())
        }

    private fun onUi(ui: UiLifecycle): LcStep = when {
        ui == UiLifecycle.ACTIVE -> if (state == NodeState.FROZEN) resume() else same()
        state == NodeState.IDLE || state == NodeState.ACTIVE -> freeze()
        else -> same()
    }

    private fun onBodySent(id: String): LcStep {
        if (id !in attempts || state != NodeState.ACTIVE) return same()
        bodies += id
        if (displayHeld) return same()
        displayHeld = true
        return same(listOf(LcEffect.HoldDisplay))
    }

    private fun onAttemptFinished(id: String, now: Long): LcStep {
        if (!attempts.remove(id)) return same()
        bodies.remove(id)
        lastWorkMs = now
        if (bodies.isEmpty() && displayHeld) {
            displayHeld = false
            return same(listOf(LcEffect.ReleaseDisplay))
        }
        return same()
    }

    private fun onTick(now: Long): LcStep {
        val gap = now - lastTickMs
        lastTickMs = now
        if (state == NodeState.STARTING || state == NodeState.LEDGER_FAIL || state == NodeState.RESUMING) return same()
        if (gap > WATCHDOG_GAP_MS) return resume()
        if (state == NodeState.FROZEN || state == NodeState.FREEZING) return same()
        if (now - (lastActiveMs ?: startedMs) > ACTIVE_WINDOW_MS) return freeze()
        if (state == NodeState.ACTIVE && attempts.isEmpty() && now - lastWorkMs >= IDLE_CLOSE_MS) {
            val effects = if (sessions > 0) listOf(LcEffect.CloseSessions("idle")) else emptyList()
            sessions = 0
            return moveTo(NodeState.IDLE, effects)
        }
        return same()
    }

    /** UI backgrounded, or no `active` for 10 s: cancel, close without waiting for replies, write outcome rows, force. */
    private fun freeze(): LcStep = moveTo(NodeState.FREEZING, drainEffects("freezing"))

    /** Treat every session as dead. Nothing is retried: the prompt already left, and a retry would leave it a second time. */
    private fun resume(): LcStep = moveTo(NodeState.RESUMING, drainEffects("suspend"))

    private fun onDrained(): LcStep {
        if (state != NodeState.FREEZING) return same()
        state = NodeState.FROZEN
        return if (uiRecentlyActive(clock.nowMs())) resume() else LcStep(state, emptyList())
    }

    private fun drainEffects(closedBy: String): List<LcEffect> {
        val effects = ArrayList<LcEffect>()
        val open = attempts.toList()
        if (open.isNotEmpty() && closedBy != "suspend") effects += LcEffect.CancelAttempts
        if (sessions > 0) effects += LcEffect.CloseSessions(closedBy)
        if (open.isNotEmpty()) effects += LcEffect.WriteInterruptedRows(open)
        if (displayHeld) effects += LcEffect.ReleaseDisplay
        effects += LcEffect.ForceLedger
        attempts.clear()
        bodies.clear()
        sessions = 0
        displayHeld = false
        return effects
    }

    private fun ledgerFail(): LcStep {
        val effects = ArrayList<LcEffect>()
        if (attempts.isNotEmpty()) effects += LcEffect.CancelAttempts
        if (displayHeld) effects += LcEffect.ReleaseDisplay
        attempts.clear()
        bodies.clear()
        sessions = 0
        displayHeld = false
        return moveTo(NodeState.LEDGER_FAIL, effects)
    }

    private fun shutdown(): LcStep {
        val effects = if (state == NodeState.LEDGER_FAIL) {
            val e = ArrayList<LcEffect>()
            if (displayHeld) e += LcEffect.ReleaseDisplay
            e
        } else {
            drainEffects("shutdown").toMutableList()
        }
        effects += LcEffect.ExitNow
        stopped = true
        return LcStep(state, effects)
    }

    companion object {
        const val ACTIVE_WINDOW_MS = 10_000L
        const val WATCHDOG_GAP_MS = 3_000L
        const val IDLE_CLOSE_MS = 300_000L
    }
}
