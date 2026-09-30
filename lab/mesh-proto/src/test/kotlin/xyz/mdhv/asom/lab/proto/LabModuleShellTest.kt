package xyz.mdhv.asom.lab.proto

import kotlin.test.Test
import kotlin.test.assertEquals

class LabModuleShellTest {
    @Test
    fun shellCompilesAndRuns() {
        assertEquals("mesh-proto", LabModule.NAME)
    }
}
