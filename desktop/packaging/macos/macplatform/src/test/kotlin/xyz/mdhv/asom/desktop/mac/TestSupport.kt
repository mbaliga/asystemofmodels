package xyz.mdhv.asom.desktop.mac

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import xyz.mdhv.asom.desktop.MonotonicClock
import xyz.mdhv.asom.desktop.NodeEnv

/** Report lines go to stdout and to `build/reports/desktop/report.txt`. */
object Report {
    @Synchronized
    fun line(text: String) {
        println(text)
        val f = System.getProperty("asom.report")?.let { File(it) } ?: return
        f.parentFile.mkdirs()
        f.appendText(text + "\n", Charsets.UTF_8)
    }
}

/** Non-vacuity: a named law counts the cases that exercised it, and [assertAllExercised] fails if any count is zero. */
class LawCounter(private val laws: Collection<String>) {
    private val counts = ConcurrentHashMap<String, AtomicLong>().also { m -> laws.forEach { m[it] = AtomicLong() } }

    fun hit(law: String, n: Long = 1) {
        counts.getValue(law).addAndGet(n)
    }

    fun count(law: String): Long = counts.getValue(law).get()

    fun assertAllExercised(family: String) {
        for (l in laws) Report.line("law $family/$l: cases exercised: ${count(l)}")
        val zero = laws.filter { count(it) == 0L }
        check(zero.isEmpty()) { "vacuous laws in $family (exercised zero cases): $zero" }
    }
}

class Captured(val out: ByteArrayOutputStream = ByteArrayOutputStream(), val err: ByteArrayOutputStream = ByteArrayOutputStream()) {
    val outText: String get() = out.toString(Charsets.UTF_8)
    val errText: String get() = err.toString(Charsets.UTF_8)
    fun env(userName: String = "alice", vars: Map<String, String> = emptyMap()) =
        NodeEnv(userName, vars, PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
}

class FakeClock(var now: Long = 1_000_000L) : MonotonicClock {
    override fun nowMs(): Long = now
}

/** A short parent for temp homes: macOS TMPDIR (/var/folders/.../T) is long enough to push the control socket path over its limit. */
fun shortTempBase(): java.nio.file.Path = java.nio.file.Path.of("/tmp").takeIf { java.nio.file.Files.isDirectory(it) } ?: java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))

fun repoRoot(): File = File(System.getProperty("asom.repoRoot") ?: error("asom.repoRoot not set"))

fun moduleDir(): File = File(System.getProperty("asom.moduleDir") ?: error("asom.moduleDir not set"))
