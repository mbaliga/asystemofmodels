package xyz.mdhv.asom.catalogue

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Catalogue schema (brief §6). The catalogue is CONSUMED, not owned — source
 * of truth is the owner's news-app repo; until `CATALOGUE_URL` is provided
 * everything runs from the committed fixture `fixtures/catalogue.v1.json`.
 */
@Serializable
data class Catalogue(
    val version: Int,
    val updatedAt: String,
    val providers: List<ProviderEntry>,
    val models: List<ModelEntry>,
) {
    fun provider(id: String): ProviderEntry? = providers.firstOrNull { it.id == id }
    fun model(id: String): ModelEntry? = models.firstOrNull { it.id == id }
}

/** Driver selection (§6): `kind` is the only dispatch mechanism. */
@Serializable
enum class ProviderKind {
    /** Generic baseUrl-parameterized OpenAI-compatible driver. */
    @SerialName("openai-compat")
    OPENAI_COMPAT,

    /** Native Anthropic Messages API driver. */
    @SerialName("anthropic")
    ANTHROPIC,
}

@Serializable
data class AuthSpec(
    val type: String,
)

@Serializable
data class RateLimit(
    val rpm: Int? = null,
    val rpd: Int? = null,
)

/** Per-model pricing in USD per million tokens. */
@Serializable
data class Pricing(
    val inPerMTok: Double,
    val outPerMTok: Double,
)

@Serializable
data class ProviderEntry(
    val id: String,
    val displayName: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val auth: AuthSpec,
    val trainsOnData: Boolean,
    val programmaticAllowed: Boolean,
    val rate: RateLimit? = null,
    /** modelId → pricing. Absence means cost is not derivable for that model. */
    val pricing: Map<String, Pricing> = emptyMap(),
    val models: List<String> = emptyList(),
) {
    fun serves(modelId: String): Boolean = modelId in models
}

@Serializable
data class ModelFile(
    val url: String,
    val sha256: String,
    val bytes: Long,
    val quant: String? = null,
)

@Serializable
data class ModelEntry(
    val id: String,
    val family: String,
    val kind: String,
    val ctx: Long? = null,
    /**
     * Reasoning rank for the `best-reasoning` policy (§7): lower = better,
     * rank 1 is the strongest reasoner. Unranked models sort last.
     */
    val rank: Int? = null,
    /** Downloadable weights (for local sharing, §10). Empty for cloud-only. */
    val files: List<ModelFile> = emptyList(),
)
