package xyz.mdhv.asom.desktop.mac.exec

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class RunResult(val exitCode: Int, val stdout: ByteArray, val timedOut: Boolean = false) {
    val text: String get() = String(stdout, Charsets.UTF_8)
}

interface ProcessRunner {
    /** Runs one of the few read-only system tools the doctor is allowed to read from. Never a shell. */
    fun run(executable: String, args: List<String>, timeoutMs: Long): RunResult
}

/**
 * The doctor's only way to run a system tool, and the only file of this module besides the helper launcher that starts a process.
 * Each tool is named by absolute path with a fixed argument list; anything else is refused before anything runs. The
 * environment is empty, stdin is closed, stderr is discarded, output is capped, and a tool that overruns is killed.
 * The tools read state, they never change it: asom never calls `socketfilterfw` to set anything and never changes `pmset` settings
 * (macos.md 2 and 4.1).
 */
class SystemProcessRunner : ProcessRunner {
    /** Null when the call is allowed; otherwise the reason it is refused. */
    fun refusal(executable: String, args: List<String>): String? {
        val allowed = ALLOWED[executable] ?: return "$executable is not on the read-only allowlist"
        return if (allowed.any { it(args) }) null else "the arguments $args are not on the read-only allowlist for $executable"
    }

    override fun run(executable: String, args: List<String>, timeoutMs: Long): RunResult {
        refusal(executable, args)?.let { throw IllegalArgumentException(it) }
        val pb = ProcessBuilder(listOf(executable) + args)
        pb.environment().clear()
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        pb.redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
        val p = pb.start()
        val out = ByteArrayOutputStream()
        val reader = Thread {
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = p.inputStream.read(buf)
                    if (n < 0) break
                    if (out.size() < MAX_OUTPUT_BYTES) out.write(buf, 0, minOf(n, MAX_OUTPUT_BYTES - out.size()))
                }
            } catch (_: java.io.IOException) {
                // the process was killed
            }
        }.also { it.isDaemon = true; it.start() }
        val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) p.destroyForcibly()
        reader.join(1_000)
        return RunResult(if (done) p.exitValue() else -1, out.toByteArray(), timedOut = !done)
    }

    companion object {
        const val MAX_OUTPUT_BYTES = 256 * 1024
        const val PMSET = "/usr/bin/pmset"
        const val SOCKETFILTERFW = "/usr/libexec/ApplicationFirewall/socketfilterfw"
        const val IOREG = "/usr/sbin/ioreg"
        const val PGREP = "/usr/bin/pgrep"

        private val ALLOWED: Map<String, List<(List<String>) -> Boolean>> = mapOf(
            PMSET to listOf({ a -> a == listOf("-g", "assertions") }, { a -> a == listOf("-g", "batt") }),
            SOCKETFILTERFW to listOf(
                { a -> a == listOf("--getglobalstate") },
                { a -> a.size == 2 && a[0] == "--getappblocked" && a[1].startsWith("/") && a[1].none { it < ' ' } },
            ),
            IOREG to listOf({ a -> a == listOf("-r", "-k", "AppleClamshellState", "-d", "4") }),
            PGREP to listOf({ a -> a == listOf("-fl", "tailscaled") }),
        )
    }
}
