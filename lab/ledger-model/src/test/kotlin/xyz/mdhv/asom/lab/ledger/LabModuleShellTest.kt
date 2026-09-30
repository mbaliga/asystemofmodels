package xyz.mdhv.asom.lab.ledger

import kotlin.test.Test
import kotlin.test.assertEquals

class LabModuleShellTest {
    @Test
    fun shellCompilesAndRuns() {
        assertEquals("ledger-model", LabModule.NAME)
    }
}
