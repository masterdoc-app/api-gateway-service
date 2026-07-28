package pro.masterdoc.gateway

import kotlinx.serialization.Serializable

@Serializable
data class FeatureDefinitionDto(
    val id: String,
    val titleRu: String,
)

@Serializable
data class FeaturesCatalogResponse(
    val items: List<FeatureDefinitionDto>,
)

/**
 * Product feature catalog (must stay aligned with feature-service FeatureCatalog).
 * Serves invite UI labels and validates invite/set-features payloads.
 */
object ProductFeatures {
    val ENTRIES: List<FeatureDefinitionDto> =
        listOf(
            FeatureDefinitionDto("admin", "Админ"),
            FeatureDefinitionDto("black_box", "Чёрный ящик"),
            FeatureDefinitionDto("board", "Доска"),
            FeatureDefinitionDto("charts", "ППР"),
            FeatureDefinitionDto("equipment", "Оборудование"),
        )

    val ALL: Set<String> = ENTRIES.map { it.id }.toSet()

    fun catalog(): FeaturesCatalogResponse = FeaturesCatalogResponse(items = ENTRIES.sortedBy { it.id })

    /** @return error message or null if valid */
    fun validate(features: List<String>): String? {
        if (features.isEmpty()) return "features must not be empty"
        for (f in features) {
            if (f !in ALL) return "Unknown feature: $f"
        }
        return null
    }
}
