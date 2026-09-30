package xyz.mdhv.asom.lab.conformance

import kotlin.system.exitProcess

/**
 * `lines <F1,F2,...>`: one line per vector, sorted by id: `<id> ok` or `<id> reject <CODE>`. This is the
 * implementation's own verdict, not pass/fail, so two lanes can be diffed byte for byte (LAB_SPEC 3.3).
 * Vectors that cannot run in this build (proposed, module absent) print nothing. Opens a loopback listener
 * only for the server-driven families, exactly as their tests do; nothing listens by default.
 */
fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] != "lines") {
        System.err.println("usage: lines <family>[,<family>...]")
        exitProcess(2)
    }
    val requested = (args.getOrNull(1) ?: L01_FAMILIES.joinToString(",")).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val loaded = VectorLoader.loadAll()
    if (loaded.problems.isNotEmpty()) {
        loaded.problems.forEach { System.err.println("ENVELOPE PROBLEM: $it") }
        exitProcess(1)
    }
    val lines = ArrayList<String>()
    for (fam in requested) {
        if (fam !in ALL_FAMILIES) {
            System.err.println("unknown family $fam")
            exitProcess(2)
        }
        val checker = checkerFor(fam)
        if (checker == null) {
            System.err.println("family $fam: not-implemented")
            continue
        }
        loaded.forFamily(fam).forEach { v -> checker.verdict(v)?.let { lines += it } }
    }
    lines.sorted().forEach { println(it) }
}
