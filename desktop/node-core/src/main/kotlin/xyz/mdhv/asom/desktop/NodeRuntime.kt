package xyz.mdhv.asom.desktop

import xyz.mdhv.asom.desktop.engine.EngineWiring
import xyz.mdhv.asom.desktop.governor.DesktopRules
import xyz.mdhv.asom.desktop.governor.FsmConfig
import xyz.mdhv.asom.desktop.governor.FsmEffect
import xyz.mdhv.asom.desktop.governor.FsmEvent
import xyz.mdhv.asom.desktop.governor.FsmStep
import xyz.mdhv.asom.desktop.governor.Governor
import xyz.mdhv.asom.desktop.governor.HostRules
import xyz.mdhv.asom.desktop.governor.HostRulesProvider
import xyz.mdhv.asom.desktop.governor.HostSignals
import xyz.mdhv.asom.desktop.governor.HostSignalsProvider
import xyz.mdhv.asom.desktop.governor.LenderState
import xyz.mdhv.asom.desktop.governor.ProviderFsm
import xyz.mdhv.asom.desktop.governor.SettlesBeforeServing
import xyz.mdhv.asom.desktop.governor.Readings

/**
 * Wires the host's ports to the governor and the provider FSM. The FSM's effects are handed to [onEffect]; nothing in
 * this wave acts on them (no listener, no lock, no engine), so a running node is OFF and inert until the owner enables
 * lending, and even then only records what it would do.
 */
class NodeRuntime(
    val platform: DesktopPlatform,
    val mode: HostMode,
    val config: NodeConfig = NodeConfig(),
    private val clock: MonotonicClock = SystemMonotonicClock,
    val engine: EngineWiring = EngineWiring(),
    private val onEffect: (FsmEffect) -> Unit = {},
) {
    val rules: HostRules = (platform as? HostRulesProvider)?.hostRules(config, mode) ?: DesktopRules(config)
    val fsm: ProviderFsm = ProviderFsm(FsmConfig(graceMs = rules.graceMs, presenceFirst = mode == HostMode.FOREGROUND, holdDownMs = config.presenceHoldDownMs))
    private val governor = Governor(config, rules, settleBeforeServing = platform is SettlesBeforeServing)

    private fun apply(event: FsmEvent): FsmStep {
        val step = fsm.apply(event, clock.nowMs())
        step.effects.forEach(onEffect)
        return step
    }

    /** The owner's explicit "start lending". Only ever called from an owner action. */
    fun enableLending(): FsmStep = apply(FsmEvent.USER_ENABLE)

    fun disableLending(): FsmStep = apply(FsmEvent.USER_DISABLE)

    /** One governor evaluation: read the ports, derive events, apply them in order. Reading a port never throws out of here. */
    fun tick(): List<FsmStep> {
        val now = clock.nowMs()
        val power = platform.power().read()
        val thermal = platform.thermal().read()
        val cpu = runCatching { platform.presence().sample().cpuOtherPermille }.getOrNull()
        val gpuPort = platform.gpuContention()
        val gpu = runCatching { gpuPort?.sample()?.otherBusyPermille }.getOrNull()
        val signals = (platform as? HostSignalsProvider)?.hostSignals() ?: HostSignals()
        val eval = governor.evaluate(now, Readings(power, thermal, signals, cpu, gpu, gpuCounterPresent = gpuPort != null))
        return eval.events.map { apply(it) }
    }

    /** Drain and stop: a user disable, then the grace of a user drain is zero, so it ends at OFF at once. */
    fun shutdown(): List<FsmStep> {
        val steps = ArrayList<FsmStep>(2)
        steps += apply(FsmEvent.USER_DISABLE)
        if (fsm.state == LenderState.DRAINING) steps += apply(FsmEvent.GRACE_EXPIRED)
        return steps
    }
}
