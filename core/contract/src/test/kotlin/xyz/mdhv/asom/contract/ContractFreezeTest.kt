package xyz.mdhv.asom.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pins the frozen public contract (brief §5) so drift fails loudly. */
class ContractFreezeTest {

    @Test
    fun `error codes are exactly the seven of §5_6`() {
        assertEquals(
            setOf(
                "NOT_PAIRED", "TOKEN_REVOKED", "NO_PROVIDER_KEY", "MODEL_UNKNOWN",
                "ALL_PROVIDERS_COOLING", "LOCAL_ENGINE_ABSENT", "UNSUPPORTED_BY_DRIVER",
            ),
            AsomErrorCode.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `local engine absent is a 501 — fails loudly, never silently`() {
        assertEquals(501, AsomErrorCode.LOCAL_ENGINE_ABSENT.httpStatus)
        assertEquals(501, AsomErrorCode.UNSUPPORTED_BY_DRIVER.httpStatus)
    }

    @Test
    fun `virtual models are exactly the five of §5_5`() {
        assertEquals(
            setOf("auto", "cheapest", "fastest", "best-reasoning", "local-only"),
            Policy.VIRTUAL_MODELS,
        )
        assertEquals(Policy.BEST_REASONING, Policy.fromWire("best-reasoning"))
        assertNull(Policy.fromWire("gpt-4o")) // concrete ids are not policies
    }

    @Test
    fun `capabilities report hasLocalEngine false in v1`() {
        val caps = Capabilities.v1(catalogueVersion = 1)
        assertEquals(false, caps.hasLocalEngine)
        assertEquals(Capabilities.V1_ENDPOINTS, caps.endpoints)
        assertEquals(listOf("auto", "best-reasoning", "cheapest", "fastest", "local-only"), caps.virtualModels)
    }

    @Test
    fun `contract constants`() {
        assertEquals("xyz.mdhv.asom.discovery", Asom.DISCOVERY_AUTHORITY)
        assertEquals("xyz.mdhv.asom.models", Asom.MODELS_AUTHORITY)
        assertEquals("xyz.mdhv.asom.PAIR", Asom.PAIRING_ACTION)
    }
}
