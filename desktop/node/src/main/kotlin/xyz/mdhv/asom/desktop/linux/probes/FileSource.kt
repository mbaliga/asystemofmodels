package xyz.mdhv.asom.desktop.linux.probes

import java.nio.file.Files
import java.nio.file.Path

/**
 * Read-only view of /sys and /proc. Probes take this instead of touching the filesystem, so the same parsers run over
 * the real tree and over the SYNTHETIC fixture trees under src/test/resources/fixtures/sysfs.
 * Paths are absolute Linux paths ("/sys/class/hwmon/hwmon0/name").
 */
interface FileSource {
    /** File text, or null if the file is absent or unreadable (permission, ENODEV on a dead sensor). */
    fun read(path: String): String?

    /** Child names, sorted; empty if the directory is absent. */
    fun list(path: String): List<String>

    /**
     * Child names, sorted; empty if the directory is ABSENT (ENOENT), and null if it exists or may exist but cannot be listed
     * (permission, I/O error, not a directory). "Nothing there" and "cannot look" are different answers (finding HLU-6).
     * The default is for in-memory sources, which cannot fail.
     */
    fun listOrNull(path: String): List<String>? = list(path)
}

class RealFileSource : FileSource {
    override fun read(path: String): String? = readBounded(Path.of(path))
    override fun list(path: String): List<String> = listDir(Path.of(path))
    override fun listOrNull(path: String): List<String>? = listDirOrNull(Path.of(path))
}

/** Maps "/x/y" to `<root>/x/y`, for fixture trees. */
class RootedFileSource(private val root: Path) : FileSource {
    private fun map(path: String): Path = root.resolve(path.removePrefix("/"))
    override fun read(path: String): String? = readBounded(map(path))
    override fun list(path: String): List<String> = listDir(map(path))
    override fun listOrNull(path: String): List<String>? = listDirOrNull(map(path))
}

private const val MAX_FILE_BYTES = 1 shl 20

private fun readBounded(p: Path): String? = try {
    Files.newInputStream(p).use { String(it.readNBytes(MAX_FILE_BYTES), Charsets.UTF_8) }
} catch (_: Exception) {
    null
}

private fun listDir(p: Path): List<String> = listDirOrNull(p) ?: emptyList()

private fun listDirOrNull(p: Path): List<String>? = try {
    Files.list(p).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
} catch (_: java.nio.file.NoSuchFileException) {
    emptyList()
} catch (_: Exception) {
    null
}

/** Parses a trimmed decimal integer file; null on anything else. */
internal fun String?.asIntOrNull(): Int? = this?.trim()?.toIntOrNull()

/** Parses a PSI or kernel decimal such as "12.34" into hundredths (1234) without floating point; null if malformed. */
internal fun parseCenti(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val dot = t.indexOf('.')
    val whole = (if (dot < 0) t else t.substring(0, dot)).toIntOrNull() ?: return null
    if (whole < 0) return null
    val frac = if (dot < 0) "" else t.substring(dot + 1)
    if (frac.any { it !in '0'..'9' }) return null
    val cents = (frac + "00").substring(0, 2).toInt()
    return whole * 100 + cents
}
