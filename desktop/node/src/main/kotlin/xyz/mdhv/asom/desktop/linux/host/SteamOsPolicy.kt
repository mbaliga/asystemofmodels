package xyz.mdhv.asom.desktop.linux.host

import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.PowerReading
import xyz.mdhv.asom.desktop.PowerSource
import xyz.mdhv.asom.desktop.governor.HostRules
import xyz.mdhv.asom.desktop.governor.HostSignals
import xyz.mdhv.asom.desktop.governor.HostVerdict

/**
 * The Steam Deck rules (linux.md 2.1, 3.3, 3.4; LD-2, LD-10):
 *  - never a block lock (in Game Mode a block lock produces a fake sleep, LF05); a delay lock only;
 *  - 2 s grace on a drain (a Vulkan dispatch cannot be pre-empted by a game, LA17);
 *  - battery is a hard NO (an unknown power source counts as battery);
 *  - lending only docked, on AC, with no game running;
 *  - Game Mode lending needs the LD-2 opt-in (`gameModeLending`), because nothing on the Deck's screen shows it.
 *
 * Signals the spec has not given a mechanism for (docked, game running, Game Mode: R3-OVERCLAIM-4) arrive as null when
 * unknown, and unknown is treated as the unsafe answer: not docked, a game running, in Game Mode. So a Deck whose host
 * cannot yet tell stays ARMED instead of guessing (desktop/ERRATA.md ERR-DECK-1). A game is a PRESENCE input (LP-0).
 */
class SteamOsPolicy(config: NodeConfig) : HostRules {
    override val label: String = "steamos-deck"
    override val graceMs: Long = config.graceMsDeck
    private val gameModeOptIn: Boolean = config.gameModeLending

    /** Fixed: no configuration can turn a block lock on for SteamOS. */
    override val blockLockAllowed: Boolean = false

    override fun evaluate(power: PowerReading, signals: HostSignals): HostVerdict {
        val condition = ArrayList<String>(3)
        val presence = ArrayList<String>(1)
        if (power.source != PowerSource.AC) condition += "battery-hard-no"
        if (signals.docked != true) condition += if (signals.docked == null) "docked-unknown" else "not-docked"
        if (signals.gameMode != false && !gameModeOptIn) condition += "game-mode-lending-needs-opt-in"
        if (signals.gameRunning != false) presence += if (signals.gameRunning == null) "game-unknown" else "game-running"
        return HostVerdict(condition, presence)
    }
}
