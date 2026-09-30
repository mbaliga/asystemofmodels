package xyz.mdhv.asom.lab.sim

import kotlin.test.Test
import kotlin.test.assertEquals

class LabModuleShellTest {
    @Test
    fun shellCompilesAndRuns() {
        assertEquals("mesh-sim", LabModule.NAME)
    }
}
