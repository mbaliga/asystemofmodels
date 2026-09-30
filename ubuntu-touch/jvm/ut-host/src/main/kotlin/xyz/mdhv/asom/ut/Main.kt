package xyz.mdhv.asom.ut

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import xyz.mdhv.asom.desktop.ExitCodes
import xyz.mdhv.asom.desktop.HostRefusedException
import xyz.mdhv.asom.desktop.MonotonicClock
import xyz.mdhv.asom.desktop.NodeEnv
import xyz.mdhv.asom.desktop.RootGuard
import xyz.mdhv.asom.desktop.SystemMonotonicClock
import xyz.mdhv.asom.lab.json.JString

/** How the JVM child was started. There is no default: a mode is always chosen on purpose. */
enum class UtMode { PROFILE_UT, SELFTEST, FAKE_UI }

object UtArgs {
    fun parse(args: List<String>): UtMode? {
        if (args.size != 1) return null
        return when (args[0]) {
            "--profile=ut" -> UtMode.PROFILE_UT
            "--selftest" -> UtMode.SELFTEST
            "--fake-ui" -> UtMode.FAKE_UI
            else -> null
        }
    }
}

/**
 * `asom-ut-node`: `--profile=ut` (the node behind the QML app: `asom-ut-ctl/1` on stdin and stdout, ends on end of input),
 * `--selftest` (prints exactly one JSON line and exits) or `--fake-ui` (the scripted node for the QML tests). It listens on
 * nothing and dials nothing.
 */
object UtMain {
    fun runProfile(vars: Map<String, String>, io: ChannelStreams, clock: MonotonicClock = SystemMonotonicClock): Int {
        val layout = try {
            UtPaths.resolve(vars).also { UtPaths.ensure(it) }
        } catch (e: HostRefusedException) {
            io.diag.emit(DiagCode.REFUSED)
            return ExitCodes.REFUSED
        }
        val writer = FrameWriter(io.output)
        val session = NodeSession(
            reader = LineReader(io.input),
            out = { writer.write(it) },
            lifecycle = NodeLifecycle(clock),
            diag = io.diag,
            openLedger = { FileLedger.open(layout.paths.ledgerDir.resolve("ledger.jsonl")) },
            selfTest = { SelfTest(vars).run() },
        )
        return withWatchdog(session, io.diag)
    }

    fun runFake(io: ChannelStreams, clock: MonotonicClock = SystemMonotonicClock): Int {
        val writer = FrameWriter(io.output)
        val session = FakeNode.session(LineReader(io.input), { writer.write(it) }, clock, io.diag)
        return withWatchdog(session, io.diag)
    }

    private fun withWatchdog(session: NodeSession, diag: Diag): Int {
        val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "asom-ut-watchdog").apply { isDaemon = true } }
        watchdog.scheduleAtFixedRate(
            {
                try {
                    session.tick()
                } catch (_: IOException) {
                } catch (_: RuntimeException) {
                    diag.emit(DiagCode.INTERNAL)
                }
            },
            WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS,
        )
        try {
            return session.run()
        } finally {
            watchdog.shutdownNow()
        }
    }

    fun runSelfTest(vars: Map<String, String>, out: PrintStream): Int {
        val result = SelfTest(vars).run()
        out.println(FrameCodec.encodeValue(result))
        out.flush()
        return if ((result["selftest"] as? JString)?.value == "ok") ExitCodes.OK else ExitCodes.ERROR
    }

    private const val WATCHDOG_PERIOD_MS = 1_000L
}

/** An uncaught exception says one fixed line and stops: the JVM's own trace could carry a message, a path or a prompt. */
object CrashGuard {
    const val EXIT_INTERNAL = 70

    fun install(diag: Diag) {
        Thread.setDefaultUncaughtExceptionHandler { _, _ ->
            diag.emit(DiagCode.INTERNAL)
            Runtime.getRuntime().halt(EXIT_INTERNAL)
        }
    }
}

fun main(args: Array<String>) {
    val rawDiag = Diag(FileOutputStream(FileDescriptor.err))
    CrashGuard.install(rawDiag)
    val mode = UtArgs.parse(args.toList())
    if (mode == null) {
        rawDiag.emit(DiagCode.BAD_ARGUMENTS)
        exitProcess(ExitCodes.USAGE)
    }
    if (mode != UtMode.FAKE_UI) {
        val sink = PrintStream(OutputStream.nullOutputStream())
        val userName = System.getProperty("user.name") ?: ""
        if (RootGuard.refusal(NodeEnv(userName, emptyMap(), sink, sink)) != null) {
            rawDiag.emit(DiagCode.REFUSED)
            exitProcess(ExitCodes.REFUSED)
        }
    }
    val vars = System.getenv()
    val code = when (mode) {
        UtMode.PROFILE_UT -> UtMain.runProfile(vars, Stdio.takeOver())
        UtMode.FAKE_UI -> UtMain.runFake(Stdio.takeOver())
        UtMode.SELFTEST -> {
            val (out, _) = Stdio.takeOverForSelfTest()
            UtMain.runSelfTest(vars, out)
        }
    }
    exitProcess(code)
}
