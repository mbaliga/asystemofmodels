package xyz.mdhv.asom.inference

import kotlinx.serialization.json.JsonObject
import xyz.mdhv.asom.contract.AsomErrorCode
import xyz.mdhv.asom.contract.AsomException

/**
 * Seam for the Phase-2 local generation engine. v1 NEVER implements this
 * beyond [NoopEngine] (brief §2): `local-only` fails loudly with a typed
 * `501 LOCAL_ENGINE_ABSENT` and capabilities report `hasLocalEngine:false`.
 */
interface LocalEngine {
    val hasLocalEngine: Boolean

    /**
     * Runs a chat completion locally. Raw-JSON in/out to mirror the §5.9
     * pass-through posture.
     *
     * @throws AsomException LOCAL_ENGINE_ABSENT when [hasLocalEngine] is false.
     */
    suspend fun chatCompletion(rawRequest: JsonObject): JsonObject
}

/** The only v1 implementation — the engine that is honest about not existing. */
object NoopEngine : LocalEngine {
    override val hasLocalEngine: Boolean = false

    override suspend fun chatCompletion(rawRequest: JsonObject): JsonObject =
        throw AsomException(
            AsomErrorCode.LOCAL_ENGINE_ABSENT,
            "asom v1 ships no local generation engine; local-only cannot be served (Phase 2)",
        )
}
