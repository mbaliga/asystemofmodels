package xyz.mdhv.asom.desktop.linux

import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import xyz.mdhv.asom.desktop.MonotonicClock
import xyz.mdhv.asom.desktop.linux.probes.FileSource
import xyz.mdhv.asom.desktop.linux.probes.RootedFileSource

object Report {
    @Synchronized
    fun line(text: String) {
        println(text)
        val f = System.getProperty("asom.report")?.let { File(it) } ?: return
        f.parentFile.mkdirs()
        f.appendText(text + "\n", Charsets.UTF_8)
    }
}

class LawCounter(private val laws: Collection<String>) {
    private val counts = ConcurrentHashMap<String, AtomicLong>().also { m -> laws.forEach { m[it] = AtomicLong() } }
    fun hit(law: String) { counts.getValue(law).incrementAndGet() }
    fun count(law: String): Long = counts.getValue(law).get()
    fun assertAllExercised(family: String) {
        for (l in laws) Report.line("law $family/$l: cases exercised: ${count(l)}")
        val zero = laws.filter { count(it) == 0L }
        check(zero.isEmpty()) { "vacuous laws in $family (exercised zero cases): $zero" }
    }
}

class FakeClock(var now: Long = 1_000_000L) : MonotonicClock {
    override fun nowMs(): Long = now
}

object Fixtures {
    val hosts = listOf("deck-oled", "deck-lcd", "dell", "ci-vm")

    private val base: Path = Path.of(System.getProperty("asom.fixtures") ?: error("asom.fixtures is not set (run through Gradle)"))

    fun dir(host: String): Path = base.resolve(host).also { check(it.toFile().isDirectory) { "missing fixture host $host" } }
    fun fs(host: String): FileSource = ColonDecodingFileSource(RootedFileSource(dir(host)))
}

/**
 * Real sysfs names contain ':' (a HID battery is "hid-0003:28DE:1205.0001-battery"), which Windows cannot store in a
 * file name, so the repository stores ':' as "%3A" and the code under test still sees the real name.
 */
const val COLON_ENCODED = "%3A"

class ColonDecodingFileSource(private val delegate: FileSource) : FileSource {
    override fun read(path: String): String? = delegate.read(path.replace(":", COLON_ENCODED))
    override fun list(path: String): List<String> =
        delegate.list(path.replace(":", COLON_ENCODED)).map { it.replace(COLON_ENCODED, ":") }
}

/** An in-memory file tree that tests mutate between samples. */
class MapFileSource(files: Map<String, String> = emptyMap()) : FileSource {
    val files: MutableMap<String, String> = LinkedHashMap(files)

    override fun read(path: String): String? = files[path]

    override fun list(path: String): List<String> {
        val prefix = path.trimEnd('/') + "/"
        return files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.distinct().sorted()
    }

    /** Copies a fixture host's tree in, so a test can start from a real-shaped tree and change one value. */
    companion object {
        fun ofFixture(host: String): MapFileSource {
            val root = Fixtures.dir(host)
            val m = LinkedHashMap<String, String>()
            root.toFile().walkTopDown().filter { it.isFile }.forEach { f ->
                m["/" + root.relativize(f.toPath()).toString().replace(File.separatorChar, '/').replace(COLON_ENCODED, ":")] = f.readText(Charsets.UTF_8)
            }
            return MapFileSource(m)
        }
    }
}
