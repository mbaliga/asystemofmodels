package xyz.mdhv.asom.desktop.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import xyz.mdhv.asom.contract.AsomErrorCode
import xyz.mdhv.asom.contract.AsomException
import xyz.mdhv.asom.inference.LocalEngine

class EngineWiringTest {
    @Test
    fun `the only engine is NoopEngine and local-only answers LOCAL_ENGINE_ABSENT`() {
        val w = EngineWiring()
        assertFalse(w.engine.hasLocalEngine)
        assertEquals("none", w.backend)
        val e = assertFailsWith<AsomException> { runBlocking { w.engine.chatCompletion(JsonObject(emptyMap())) } }
        assertEquals(AsomErrorCode.LOCAL_ENGINE_ABSENT, e.code)
    }

    @Test
    fun `an engine that claims to exist is refused this wave`() {
        val fake = object : LocalEngine {
            override val hasLocalEngine = true
            override suspend fun chatCompletion(rawRequest: JsonObject): JsonObject = rawRequest
        }
        assertFailsWith<IllegalArgumentException> { EngineWiring(fake) }
    }
}
