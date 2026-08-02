package pro.masterdoc.gateway

import kotlin.test.Test
import kotlin.test.assertEquals

class ProductFeaturesTest {
    @Test
    fun `rejects unknown feature`() {
        assertEquals("Unknown feature: foo", ProductFeatures.validate(listOf("board", "foo")))
    }

    @Test
    fun `rejects empty features`() {
        assertEquals("features must not be empty", ProductFeatures.validate(emptyList()))
    }

    @Test
    fun `accepts known features`() {
        assertEquals(null, ProductFeatures.validate(listOf("admin", "charts")))
    }

    @Test
    fun `rejects removed copilot feature`() {
        assertEquals("Unknown feature: copilot", ProductFeatures.validate(listOf("equipment", "copilot")))
    }

    @Test
    fun `catalog excludes copilot and includes ai and asset qr`() {
        assertEquals(
            listOf(
                "admin",
                "ai",
                "asset_qr",
                "black_box",
                "board",
                "charts",
                "engineer",
                "equipment",
                "map",
                "reports",
                "tickets",
            ),
            ProductFeatures.catalog().items.map { it.id },
        )
        assertEquals(
            FeatureDefinitionDto("asset_qr", "QR оборудования"),
            ProductFeatures.catalog().items.single { it.id == "asset_qr" },
        )
    }
}
