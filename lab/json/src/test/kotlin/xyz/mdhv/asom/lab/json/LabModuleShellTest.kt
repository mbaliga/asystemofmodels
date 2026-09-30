package xyz.mdhv.asom.lab.json

import kotlin.test.Test
import kotlin.test.assertEquals

class LabModuleShellTest {
    @Test
    fun shellCompilesAndRuns() {
        assertEquals("json", LabModule.NAME)
    }
}
