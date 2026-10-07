package xyz.mdhv.asom.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * `asom-node`. Modes: `--mode=system|user|foreground|selftest` (aliases `--foreground`, `--self-test`); no default, so a
 * mode is always chosen on purpose. It refuses to run as root, finds its host through `ServiceLoader<DesktopPlatform>`,
 * and prints nothing secret: it never reads a token or a key, and its own output is a fixed banner plus status text.
 * This wave the node binds no socket at all and lending stays OFF.
 */
object NodeMain {
    private const val USAGE = "usage: asom-node --mode=system|user|foreground|selftest [--config=<file>]"

    fun run(
        args: List<String>,
        env: NodeEnv,
        lookup: HostLookup,
        awaitShutdown: (NodeRuntime) -> Unit,
    ): Int {
        var modeName: String? = null
        var configPath: String? = null
        for (a in args) {
            when {
                a.startsWith("--mode=") -> modeName = a.removePrefix("--mode=")
                a == "--foreground" -> modeName = "foreground"
                a == "--self-test" -> modeName = "selftest"
                a.startsWith("--config=") -> configPath = a.removePrefix("--config=")
                a == "--version" -> { env.out.println(NodeVersion.BANNER); return ExitCodes.OK }
                a == "--help" -> { env.out.println(USAGE); return ExitCodes.OK }
                else -> { env.err.println("asom-node: unknown argument: $a"); env.err.println(USAGE); return ExitCodes.USAGE }
            }
        }
        val selftest = modeName == "selftest"
        val mode = if (selftest) HostMode.FOREGROUND else modeName?.let { HostMode.parse(it) }
        if (mode == null) {
            env.err.println(if (modeName == null) "asom-node: no mode given" else "asom-node: unknown mode: $modeName")
            env.err.println(USAGE)
            return ExitCodes.USAGE
        }

        RootGuard.refusal(env)?.let {
            env.err.println("asom-node: $it")
            return ExitCodes.REFUSED
        }
        HostFinder.failure(lookup, env)?.let { return it }
        val platform = (lookup as HostLookup.Found).platform

        val config = try {
            configPath?.let { NodeConfig.parse(Files.readString(Path.of(it), Charsets.UTF_8)) } ?: NodeConfig()
        } catch (e: NodeConfigException) {
            env.err.println("asom-node: bad config: ${e.message}")
            return ExitCodes.USAGE
        } catch (e: java.io.IOException) {
            env.err.println("asom-node: cannot read config: ${e.message}")
            return ExitCodes.USAGE
        }

        if (selftest) return SelfTest(platform, env).run()

        val rt = try {
            platform.paths(mode)
            NodeRuntime(platform, mode, config)
        } catch (e: HostRefusedException) {
            env.err.println("asom-node: ${e.message}")
            return ExitCodes.REFUSED
        }
        env.err.println(NodeVersion.BANNER)
        env.err.println("mode=${mode.cliName} host=${mode.label} fsm=${rt.fsm.state} listeners=none control-socket=NOT_YET_IMPLEMENTED(DL2)")
        awaitShutdown(rt)
        return ExitCodes.OK
    }
}

/**
 * Blocks until the JVM is asked to stop (SIGTERM, Ctrl-C). The shutdown hook drains: a user disable, then OFF. The latch
 * is never released on purpose: the JVM exits when the hook returns, and calling exit from inside shutdown would hang.
 */
internal fun blockUntilSignalled(rt: NodeRuntime) {
    Runtime.getRuntime().addShutdownHook(Thread({ rt.shutdown() }, "asom-drain"))
    CountDownLatch(1).await()
}

fun main(args: Array<String>) {
    exitProcess(NodeMain.run(args.toList(), NodeEnv.system(), HostFinder.find(), ::blockUntilSignalled))
}
