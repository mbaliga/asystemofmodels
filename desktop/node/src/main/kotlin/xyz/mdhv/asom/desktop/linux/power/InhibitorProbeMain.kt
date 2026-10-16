package xyz.mdhv.asom.desktop.linux.power

import kotlin.system.exitProcess
import xyz.mdhv.asom.desktop.LockKind
import xyz.mdhv.asom.desktop.NodeEnv

/**
 * A diagnostic, not the node: takes ONE keep-awake lock through the real [Inhibitor], prints the outcome, holds it for
 * `--seconds` and releases it. `desktop/packaging/linux/test/systemd-vm.sh` runs it as user `asom` under systemd to see
 * what real logind and polkit do with a delay lock and a block lock (CI-ONLY). It listens on nothing and reads no key.
 *
 *   java -cp <install>/lib/ (every jar) xyz.mdhv.asom.desktop.linux.power.InhibitorProbeMainKt --kind=delay|block [--seconds=N] [--deck]
 *
 * `--deck` applies the SteamOS rule (never a block lock). Exit 0 when the lock is held, 4 when it is refused.
 */
fun main(args: Array<String>) {
    val env = NodeEnv.system()
    val kind = args.firstOrNull { it.startsWith("--kind=") }?.removePrefix("--kind=")
    val seconds = args.firstOrNull { it.startsWith("--seconds=") }?.removePrefix("--seconds=")?.toLongOrNull() ?: 5L
    val deck = "--deck" in args
    val lockKind = when (kind) {
        "delay" -> LockKind.DELAY
        "block" -> LockKind.BLOCK
        else -> {
            env.err.println("usage: InhibitorProbeMain --kind=delay|block [--seconds=N] [--deck]")
            exitProcess(2)
        }
    }
    val hold = Inhibitor(blockLockAllowed = !deck).hold(lockKind)
    env.out.println("kind=${hold.kind.name.lowercase()} state=${hold.state.name} status=\"${hold.statusText}\" detail=\"${hold.detail}\"")
    env.out.flush()
    if (hold.state == HoldState.HELD) Thread.sleep(seconds * 1000)
    hold.release()
    exitProcess(if (hold.state == HoldState.REFUSED) 4 else 0)
}
