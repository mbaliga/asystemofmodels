package xyz.mdhv.asom.lab.proto.trust

import java.util.TreeMap
import kotlin.test.assertEquals

/**
 * Non-vacuity for tests (LAB_SPEC R10): every law counts the cases that exercised it, prints `law <name>: <n> cases`, and a required law with
 * zero cases fails the class. A law that cannot run on an OS must be required only there; none of these depends on the OS.
 */
class LawCounters(private val owner: String) {
    private val counts = TreeMap<String, Int>()

    @Synchronized
    fun bump(name: String, by: Int = 1) {
        counts.merge(name, by, Int::plus)
    }

    @Synchronized
    fun count(name: String): Int = counts[name] ?: 0

    @Synchronized
    fun finish(required: Set<String>) {
        counts.forEach { (k, n) -> println("  law $owner/$k: $n cases") }
        assertEquals(emptyList(), required.filter { (counts[it] ?: 0) == 0 }, "$owner: laws that exercised zero cases")
    }
}
