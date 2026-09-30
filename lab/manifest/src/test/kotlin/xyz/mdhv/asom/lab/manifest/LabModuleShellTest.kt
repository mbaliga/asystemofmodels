package xyz.mdhv.asom.lab.manifest

import kotlin.test.Test
import kotlin.test.assertEquals

class LabModuleShellTest {
    @Test
    fun shellCompilesAndRuns() {
        assertEquals("manifest", LabModule.NAME)
    }
}
