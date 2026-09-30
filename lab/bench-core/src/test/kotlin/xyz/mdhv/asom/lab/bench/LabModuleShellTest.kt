package xyz.mdhv.asom.lab.bench

import kotlin.test.Test
import kotlin.test.assertEquals

class LabModuleShellTest {
    @Test
    fun shellCompilesAndRuns() {
        assertEquals("bench-core", LabModule.NAME)
    }
}
