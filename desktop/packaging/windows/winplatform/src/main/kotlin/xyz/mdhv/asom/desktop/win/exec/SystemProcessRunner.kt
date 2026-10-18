package xyz.mdhv.asom.desktop.win.exec

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import xyz.mdhv.asom.desktop.win.api.ProcessRunner
import xyz.mdhv.asom.desktop.win.api.RunResult

/**
 * The only place in the module that starts a process, and it starts only read-only system tools by absolute path under
 * `%SystemRoot%\System32` (never a PATH lookup, never a shell, never PowerShell), with an argument prefix the tool must
 * begin with:
 *  - `netsh advfirewall firewall show rule ...` (the firewall gate's listing);
 *  - `powercfg /query ...` (the lid-close action for `asom doctor`).
 * Anything else is refused. The output is capped at 8 MiB, and a result that hit the cap says so ([RunResult.truncated]).
 */
class SystemProcessRunner(
    systemRoot: String,
    private val maxOutput: Int = MAX_OUTPUT,
    private val start: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
) : ProcessRunner {
    private val allowed: Map<String, List<String>> = mapOf(
        "$systemRoot\\System32\\netsh.exe".lowercase() to listOf("advfirewall", "firewall", "show", "rule"),
        "$systemRoot\\System32\\powercfg.exe".lowercase() to listOf("/query"),
    )

    /** Null when [executable] with [args] may run; otherwise why not. Pure, so it is tested on any OS. */
    fun refusal(executable: String, args: List<String>): String? {
        val prefix = allowed[executable.lowercase()] ?: return "refusing to run $executable: not on the read-only allowlist"
        if (args.size < prefix.size || args.take(prefix.size) != prefix) return "refusing to run $executable with these arguments: they must start with ${prefix.joinToString(" ")}"
        if (args.any { it.any { c -> c.code < 0x20 } }) return "refusing arguments with control characters"
        return null
    }

    override fun run(executable: String, args: List<String>, timeoutMs: Long): RunResult {
        refusal(executable, args)?.let { throw SecurityException(it) }
        val p = start(listOf(executable) + args)
        val out = ByteArrayOutputStream()
        val truncated = AtomicBoolean(false)
        val reader = Thread({
            try {
                // One byte past the cap tells "exactly the cap" from "more than the cap".
                val got = p.inputStream.readNBytes(maxOutput + 1)
                if (got.size > maxOutput) {
                    truncated.set(true)
                    out.write(got, 0, maxOutput)
                    p.destroyForcibly()
                } else {
                    out.write(got)
                }
            } catch (_: Exception) {
            }
        }, "asom-exec-reader")
        reader.isDaemon = true
        reader.start()
        val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) p.destroyForcibly()
        reader.join(2_000)
        return RunResult(if (finished) p.exitValue() else -1, out.toByteArray(), timedOut = !finished && !truncated.get(), truncated = truncated.get())
    }

    companion object {
        const val MAX_OUTPUT = 8 * 1024 * 1024
    }
}
