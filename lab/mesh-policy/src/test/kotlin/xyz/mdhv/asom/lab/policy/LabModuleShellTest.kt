package xyz.mdhv.asom.lab.policy

import kotlin.test.Test
import kotlin.test.assertEquals

class LabModuleShellTest {
    @Test
    fun shellCompilesAndRuns() {
        assertEquals("mesh-policy", LabModule.NAME)
    }
}
