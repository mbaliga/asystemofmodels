package xyz.mdhv.asom.desktop.governor

import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.PowerReading
import xyz.mdhv.asom.desktop.PowerSource

/**
 * The default desktop and laptop rules (linux.md 3.4): a machine with a battery serves only on AC ("laptop on battery
 * stays ARMED"); a machine with none is on `ac`; an unknown power source blocks (conservative).
 */
class DesktopRules(config: NodeConfig) : HostRules {
    override val label: String = "desktop"
    override val graceMs: Long = config.graceMsDesktop
    override val blockLockAllowed: Boolean = true

    override fun evaluate(power: PowerReading, signals: HostSignals): HostVerdict {
        val blocks = ArrayList<String>(1)
        when {
            power.source == PowerSource.UNKNOWN -> blocks += "power-unknown"
            power.hasBattery && power.source != PowerSource.AC -> blocks += "on-battery"
        }
        return HostVerdict(conditionBlocks = blocks, presenceBlocks = emptyList())
    }
}
