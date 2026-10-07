package xyz.mdhv.asom.catalogue

import kotlinx.serialization.json.Json

/** Thrown when catalogue JSON is syntactically valid but semantically broken. */
class CatalogueValidationException(message: String) : Exception(message)

object CatalogueParser {

    // Tolerant of unknown keys: the news-app catalogue may carry fields asom
    // doesn't consume. Unknown *values* of frozen enums (provider `kind`)
    // still fail — a provider we can't drive must not be silently mis-driven.
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun parse(raw: String): Catalogue {
        val catalogue = json.decodeFromString<Catalogue>(raw)
        validate(catalogue)
        return catalogue
    }

    private fun validate(catalogue: Catalogue) {
        if (catalogue.version != 1) {
            throw CatalogueValidationException("unsupported catalogue version ${catalogue.version} (expected 1)")
        }
        val providerIds = catalogue.providers.map { it.id }
        if (providerIds.size != providerIds.toSet().size) {
            throw CatalogueValidationException("duplicate provider ids: $providerIds")
        }
        val modelIds = catalogue.models.map { it.id }
        if (modelIds.size != modelIds.toSet().size) {
            throw CatalogueValidationException("duplicate model ids: $modelIds")
        }
        // Providers may serve models the catalogue doesn't describe in detail,
        // but pricing keys must reference models the provider actually serves.
        for (p in catalogue.providers) {
            val orphanPricing = p.pricing.keys - p.models.toSet()
            if (orphanPricing.isNotEmpty()) {
                throw CatalogueValidationException(
                    "provider '${p.id}' prices models it does not serve: $orphanPricing",
                )
            }
        }
    }
}
