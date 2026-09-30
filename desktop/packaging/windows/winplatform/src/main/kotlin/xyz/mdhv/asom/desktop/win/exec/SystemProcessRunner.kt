package xyz.mdhv.asom.desktop.win.exec

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import xyz.mdhv.asom.desktop.win.api.ProcessRunner
import xyz.mdhv.asom.desktop.win.api.RunResult

/**
 * The only place in the module that starts a process, and it starts only read-only system tools by absolute path under
 * `%SystemRoot%\System32` (never a PATH lookup, never a shell, never PowerShell), with an argument prefix the tool must
 * begin with:
 *  - `netsh advfirewall firewall show rule ...` (the firewall gate's listing);
 *  - `powercfg /query ...` (the lid-close action for `asom doctor`).
 * Anything else is refused. The output is capped at 8 MiB.
 */
class SystemProcessRunner(systemRoot: String) : ProcessRunner {
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
        val p = ProcessBuilder(listOf(executable) + args).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        val reader = Thread({
            try {
                out.write(p.inputStream.readNBytes(MAX_OUTPUT))
            } catch (_: Exception) {
            }
        }, "asom-exec-reader")
        reader.isDaemon = true
        reader.start()
        val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) p.destroyForcibly()
        reader.join(2_000)
        return RunResult(if (finished) p.exitValue() else -1, out.toByteArray(), timedOut = !finished)
    }

    companion object {
        const val MAX_OUTPUT = 8 * 1024 * 1024
    }
}
