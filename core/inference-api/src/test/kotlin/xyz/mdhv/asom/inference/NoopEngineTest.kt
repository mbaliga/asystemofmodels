package xyz.mdhv.asom.inference

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import xyz.mdhv.asom.contract.AsomErrorCode
import xyz.mdhv.asom.contract.AsomException

class NoopEngineTest {

    @Test
    fun `noop engine reports no local engine`() {
        assertFalse(NoopEngine.hasLocalEngine)
    }

    @Test
    fun `noop engine fails loudly with typed LOCAL_ENGINE_ABSENT`() {
        val ex = assertFailsWith<AsomException> {
            runBlocking { NoopEngine.chatCompletion(buildJsonObject { }) }
        }
        assertEquals(AsomErrorCode.LOCAL_ENGINE_ABSENT, ex.code)
        assertEquals(501, ex.code.httpStatus)
    }
}
