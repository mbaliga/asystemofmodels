package xyz.mdhv.asom.lab.proto.wire

import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder

/**
 * The wire laws, each over many cases, each printing `law wire/<name>: <n> cases`. The last test fails when any required law exercised zero cases
 * (LAB_SPEC R10). Evidence label: LAB, oracle: self; not device evidence.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WireLawsTest {
    companion object {
        val counts = ConcurrentHashMap<String, Int>()

        val required = listOf(
            "frame-length-bounds", "stream-parity", "unknown-type", "extension-skip", "strict-json", "size-limits", "no-message-member", "state-producer-strict", "version-rule",
            "incremental-split",
        )
    }

    private fun law(name: String, run: () -> Int) {
        val n = run()
        counts[name] = n
        println("law wire/$name: $n cases")
        assertTrue(n > 0, "law $name exercised zero cases")
    }

    @Test @Order(1) fun frameLengthBounds() = law("frame-length-bounds", WireLaws::frameLengthBounds)

    @Test @Order(2) fun streamParity() = law("stream-parity", WireLaws::streamParity)

    @Test @Order(3) fun unknownType() = law("unknown-type", WireLaws::unknownType)

    @Test @Order(4) fun extensionSkip() = law("extension-skip", WireLaws::extensionSkip)

    @Test @Order(5) fun strictJson() = law("strict-json", WireLaws::strictJson)

    @Test @Order(6) fun sizeLimits() = law("size-limits", WireLaws::sizeLimits)

    @Test @Order(7) fun noMessageMember() = law("no-message-member", WireLaws::noMessageMember)

    @Test @Order(8) fun stateProducerStrict() = law("state-producer-strict", WireLaws::stateProducerStrict)

    @Test @Order(9) fun versionRule() = law("version-rule", WireLaws::versionRule)

    @Test @Order(10) fun incrementalSplit() = law("incremental-split", WireLaws::incrementalSplit)

    @Test @Order(100)
    fun noRequiredLawIsVacuous() {
        val missing = required.filter { (counts[it] ?: 0) == 0 }
        assertEquals(emptyList(), missing, "required wire laws that exercised zero cases")
    }
}
