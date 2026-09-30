package xyz.mdhv.asom.lab.router

import kotlin.test.Test
import kotlin.test.assertEquals

class LabModuleShellTest {
    @Test
    fun shellCompilesAndRuns() {
        assertEquals("mesh-router", LabModule.NAME)
    }
}
