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
    fun `catalog excludes removed features and includes ai`() {
        assertEquals(
            listOf(
                "admin",
                "ai",
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
        assertEquals("Unknown feature: asset_qr", ProductFeatures.validate(listOf("asset_qr")))
    }
}
