package xyz.mdhv.asom.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeConfigTest {
    @Test
    fun `defaults have lending OFF and the spec thresholds`() {
        val c = NodeConfig.parse("{}")
        assertFalse(c.lending)
        assertFalse(c.gameModeLending)
        assertEquals(400, c.cpuOtherDrainPermille)
        assertEquals(200, c.eligiblePermille)
        assertEquals(600_000, c.presenceHoldDownMs)
        assertEquals(30_000, c.graceMsDesktop)
        assertEquals(2_000, c.graceMsDeck)
    }

    @Test
    fun `values parse and an unknown key is an error, not a silent ignore`() {
        val c = NodeConfig.parse("""{"lending":true,"overlayInterface":"tailscale0","confirmedLans":["ab12"],"graceMsDeck":1500,"presenceHoldDownMs":900000}""")
        assertTrue(c.lending)
        assertEquals("tailscale0", c.overlayInterface)
        assertEquals(listOf("ab12"), c.confirmedLans)
        assertEquals(1500, c.graceMsDeck)
        assertEquals(900_000, c.presenceHoldDownMs)
        assertFailsWith<NodeConfigException> { NodeConfig.parse("""{"lendign":true}""") }
    }

    @Test
    fun `the hold-down cannot be shortened below the spec and integers only`() {
        assertFailsWith<NodeConfigException> { NodeConfig.parse("""{"presenceHoldDownMs":599999}""") }
        assertFailsWith<NodeConfigException> { NodeConfig.parse("""{"sampleIntervalMs":2000.0}""") }
        assertFailsWith<NodeConfigException> { NodeConfig.parse("""{"sampleIntervalMs":2e3}""") }
        assertFailsWith<NodeConfigException> { NodeConfig.parse("""{"sampleIntervalMs":"2000"}""") }
        assertFailsWith<NodeConfigException> { NodeConfig.parse("""{"lending":1}""") }
        assertFailsWith<NodeConfigException> { NodeConfig.parse("""{"lending":true,"lending":false}""") }
        assertFailsWith<NodeConfigException> { NodeConfig.parse("[]") }
        assertFailsWith<NodeConfigException> { NodeConfig.parse("""{"cpuOtherDrainPermille":1001}""") }
        assertFailsWith<NodeConfigException> { NodeConfig.parse("""{"eligiblePermille":500}""") }
    }
}
