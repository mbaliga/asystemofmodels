package xyz.mdhv.asom.desktop.linux.packaging

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assumptions

object Pkg {
    val repoRoot: Path = Path.of(System.getProperty("asom.repoRoot") ?: error("asom.repoRoot is not set (run through Gradle)"))
    val linux: Path = repoRoot.resolve("desktop/packaging/linux")
    val systemUnit: Path = linux.resolve("systemd/asom.service")
    val userUnit: Path = linux.resolve("systemd/asom-user.service")
    val sysusers: Path = linux.resolve("sysusers.d/asom.conf")
    val polkitRule: Path = linux.resolve("polkit/50-asom-inhibit.rules")
    val journalHygiene: Path = linux.resolve("test/journal-hygiene.sh")
    val systemdVm: Path = linux.resolve("test/systemd-vm.sh")

    fun which(name: String): String? =
        (System.getenv("PATH") ?: "").split(':').map { File(it, name) }.firstOrNull { it.canExecute() }?.path
            ?: listOf("/usr/bin", "/bin", "/usr/sbin", "/sbin", "/usr/local/bin").map { File(it, name) }.firstOrNull { it.canExecute() }?.path

    /**
     * Fails the job when [name] is absent and [requireVar] is `1` (CI sets it for the tools it installs); otherwise skips
     * the test with a reason, visibly (a skipped test is reported as skipped, never as passed).
     */
    fun toolOrSkip(name: String, requireVar: String): String {
        val p = which(name)
        if (p != null) return p
        if (System.getenv(requireVar) == "1") throw AssertionError("$requireVar=1 but `$name` was not found: this job must not skip")
        Assumptions.assumeTrue(false, "`$name` not found; set $requireVar=1 to make this a failure")
        error("unreachable")
    }

    class Run(val exit: Int, val out: String)

    fun run(cmd: List<String>, env: Map<String, String> = emptyMap(), dir: File? = null, timeoutSec: Long = 60): Run {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().putAll(env)
        if (dir != null) pb.directory(dir)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(timeoutSec, TimeUnit.SECONDS)) { "timed out: $cmd" }
        return Run(p.exitValue(), out)
    }

    /** Key=value lines of one section of a unit file, in order. Comments and blank lines are dropped. */
    fun sections(unit: Path): Map<String, List<Pair<String, String>>> {
        val out = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        var cur: MutableList<Pair<String, String>>? = null
        for (raw in Files.readAllLines(unit)) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                cur = out.getOrPut(line.substring(1, line.length - 1)) { ArrayList() }
                continue
            }
            val i = line.indexOf('=')
            require(i > 0 && cur != null) { "unparseable unit line in $unit: $raw" }
            cur.add(line.substring(0, i) to line.substring(i + 1))
        }
        return out
    }
}
