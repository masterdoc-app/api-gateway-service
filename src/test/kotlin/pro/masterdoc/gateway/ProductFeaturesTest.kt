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
        assertEquals(null, ProductFeatures.validate(listOf("user_invite", "charts")))
    }
}
