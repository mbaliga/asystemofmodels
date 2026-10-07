package xyz.mdhv.asom.contract

import kotlinx.serialization.Serializable

/**
 * Capabilities JSON exposed by the discovery ContentProvider (brief §5.1).
 * v1 always reports `hasLocalEngine: false` (brief §2).
 */
@Serializable
data class Capabilities(
    val endpoints: List<String>,
    val virtualModels: List<String>,
    val hasLocalEngine: Boolean = false,
    val catalogueVersion: Int,
) {
    companion object {
        /** The frozen §5.2 endpoint list. */
        val V1_ENDPOINTS: List<String> = listOf(
            "/v1/chat/completions",
            "/v1/completions",
            "/v1/embeddings",
            "/v1/models",
            "/admin/health",
            "/admin/catalogue",
        )

        fun v1(catalogueVersion: Int): Capabilities = Capabilities(
            endpoints = V1_ENDPOINTS,
            virtualModels = Policy.VIRTUAL_MODELS.toList().sorted(),
            hasLocalEngine = false,
            catalogueVersion = catalogueVersion,
        )
    }
}
