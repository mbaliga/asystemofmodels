package xyz.mdhv.asom.desktop.win

import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import xyz.mdhv.asom.desktop.DesktopPlatform
import xyz.mdhv.asom.desktop.ExitCodes
import xyz.mdhv.asom.desktop.HostLookup
import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.NodeEnv
import xyz.mdhv.asom.desktop.NodeMain
import xyz.mdhv.asom.desktop.NodeRuntime
import xyz.mdhv.asom.desktop.win.jna.Kernel32Mutex

/**
 * The user-mode entry (`asom.exe`, the jpackage GUI launcher, should name `xyz.mdhv.asom.desktop.win.WinMainKt` as its main
 * class instead of `xyz.mdhv.asom.desktop.MainKt`): it takes the `Global\asom-node` mutex, then runs the shared `NodeMain`
 * with this host. The mutex is what makes "exactly one node identity per machine" true when the service and a user session
 * both try to start; `NodeMain` itself has no such hook. A run that ends in the self-test (the last mode argument is
 * `selftest`), `--version` and `--help` do not take it, so the self-test can run beside a live node.
 */
object WinNodeMain {
    /**
     * True when [NodeMain] could start a node for [args], so the single-identity mutex must be held first. It follows the
     * parse `NodeMain` uses (the LAST mode argument wins; `--version` and `--help` answer at once), so `--self-test
     * --mode=user` takes the mutex: it runs a node. Every argument it does not understand takes the mutex too, which is the
     * safe side (an unknown argument makes `NodeMain` refuse, and holding the mutex for that costs nothing). A test runs
     * `NodeMain` itself over a table of argument lists and fails if this ever answers false for one that starts a node.
     */
    fun takesMutex(args: List<String>): Boolean {
        var mode: String? = null
        for (a in args) {
            when {
                a.startsWith("--mode=") -> mode = a.removePrefix("--mode=")
                a == "--foreground" -> mode = "foreground"
                a == "--self-test" -> mode = "selftest"
                a.startsWith("--config=") -> {}
                a == "--version" || a == "--help" -> return false
                else -> return true
            }
        }
        return mode != "selftest"
    }

    fun run(
        args: List<String>,
        env: NodeEnv,
        platform: DesktopPlatform,
        mutex: NodeMutex,
        awaitShutdown: (NodeRuntime) -> Unit,
    ): Int {
        val lookup = HostLookup.Found(platform)
        if (!takesMutex(args)) return NodeMain.run(args, env, lookup, awaitShutdown)
        val held = try {
            mutex.acquire()
        } catch (e: HostRefusedException) {
            env.err.println("asom-node: ${e.message}")
            return ExitCodes.REFUSED
        }
        return try {
            NodeMain.run(args, env, lookup, awaitShutdown)
        } finally {
            held.close()
        }
    }

    /** Blocks until the JVM is asked to stop; the shutdown hook drains (a user disable, then OFF). */
    fun blockUntilSignalled(rt: NodeRuntime) {
        Runtime.getRuntime().addShutdownHook(Thread({ rt.shutdown() }, "asom-drain"))
        CountDownLatch(1).await()
    }
}

fun main(args: Array<String>) {
    exitProcess(WinNodeMain.run(args.toList(), NodeEnv.system(), WinPlatform(), NodeMutex(Kernel32Mutex()), WinNodeMain::blockUntilSignalled))
}
