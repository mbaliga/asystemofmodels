package xyz.mdhv.asom.desktop.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mdhv.asom.desktop.DesktopPlatform
import xyz.mdhv.asom.desktop.ExitCodes
import xyz.mdhv.asom.desktop.HostFinder
import xyz.mdhv.asom.desktop.HostLookup
import xyz.mdhv.asom.desktop.HostMode
import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.NodeConfig
import xyz.mdhv.asom.desktop.NodeEnv
import xyz.mdhv.asom.desktop.NodeRuntime
import xyz.mdhv.asom.desktop.RootGuard
import xyz.mdhv.asom.desktop.control.ControlCode

/** A command the owner CLI knows by name but a later track builds. */
private class Stub(val name: String, val track: String)

/**
 * The `asom` owner CLI. Implemented this wave: `status [--json] [--mode=<m>]`. Every other command in the surface
 * (linux.md 10.2) exists as a stub that answers with a typed NOT_IMPLEMENTED and exit code 3, never silence and never a
 * partial action. Confirmations for the commands that need one go through [TtyConfirm] only (a later track wires them).
 */
class AsomCli(private val env: NodeEnv, private val platform: DesktopPlatform, val tty: TtyConfirm = TtyConfirm()) {

    fun run(args: List<String>): Int {
        RootGuard.refusal(env)?.let {
            env.err.println("asom: $it")
            return ExitCodes.REFUSED
        }
        val rest = args.toMutableList()
        val json = rest.remove("--json")
        val modeArg = rest.firstOrNull { it.startsWith("--mode=") }?.also { rest.remove(it) }
        val mode = if (modeArg == null) HostMode.FOREGROUND else HostMode.parse(modeArg.removePrefix("--mode="))
            ?: return usage("unknown mode in $modeArg")
        if (rest.isEmpty()) return usage("no command")

        val stub = resolveStub(rest)
        return when {
            rest[0] == "status" && rest.size == 1 -> status(mode, json)
            stub != null -> notImplemented(stub, json)
            else -> usage("unknown command: ${rest.joinToString(" ")}")
        }
    }

    private fun status(mode: HostMode, json: Boolean): Int {
        val rt = try {
            NodeRuntime(platform, mode, NodeConfig())
        } catch (e: HostRefusedException) {
            env.err.println("asom: ${e.message}")
            return ExitCodes.REFUSED
        }
        val report: JsonObject = try {
            StatusReport.build(rt)
        } catch (e: HostRefusedException) {
            env.err.println("asom: ${e.message}")
            return ExitCodes.REFUSED
        }
        if (json) env.out.println(report.toString()) else env.out.print(StatusReport.human(report))
        return ExitCodes.OK
    }

    private fun notImplemented(stub: Stub, json: Boolean): Int {
        if (json) {
            env.out.println(
                buildJsonObject {
                    put("ok", false)
                    put("code", ControlCode.NOT_IMPLEMENTED.name)
                    put("command", stub.name)
                    put("track", stub.track)
                }.toString(),
            )
        } else {
            env.err.println("asom ${stub.name}: NOT_IMPLEMENTED (${stub.track}; not built in this wave)")
        }
        return ExitCodes.NOT_IMPLEMENTED
    }

    private fun usage(why: String): Int {
        env.err.println("asom: $why")
        env.err.println("usage: asom status [--json] [--mode=system|user|foreground]")
        env.err.println("       asom " + STUBS.joinToString(" | ") { it.name } + "   (NOT_IMPLEMENTED in this wave)")
        return ExitCodes.USAGE
    }

    private fun resolveStub(rest: List<String>): Stub? {
        // Two-word commands first ("lan confirm", "ledger export"), then one-word ones; extra arguments are ignored by a stub.
        val two = if (rest.size >= 2) "${rest[0]} ${rest[1]}" else null
        return STUBS.firstOrNull { it.name == two } ?: STUBS.firstOrNull { it.name == rest[0] }
    }

    companion object {
        private val STUBS = listOf(
            Stub("watch", "DL2"), Stub("lend", "DL2"), Stub("unlock", "DL2"), Stub("ledger export", "DL2"),
            Stub("doctor", "DL2"), Stub("shutdown", "DL2"),
            Stub("install", "DL3"), Stub("uninstall", "DL3"), Stub("upgrade", "DL3"), Stub("rollback", "DL3"),
            Stub("bench", "DL5"),
            Stub("chat", "DL6"), Stub("peers", "DL6"), Stub("lan confirm", "DL6"), Stub("pair-confirm", "DL6"),
            Stub("restore", "DL6"),
        )

        /** For tests: the command names the stub table covers. */
        val STUB_NAMES: List<String> get() = STUBS.map { it.name }
    }
}

/** `asom` entry point body, shared by the launcher script's main class and the tests. */
fun runAsomCli(args: List<String>, env: NodeEnv, lookup: HostLookup): Int {
    HostFinder.failure(lookup, env)?.let { return it }
    return AsomCli(env, (lookup as HostLookup.Found).platform).run(args)
}
