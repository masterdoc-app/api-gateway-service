package pro.masterdoc.gateway

import kotlin.test.Test
import kotlin.test.assertEquals

class ProductRolesTest {
    @Test
    fun `rejects unknown role`() {
        assertEquals("Unknown role: foo", ProductRoles.validate(listOf("technologist", "foo")))
    }

    @Test
    fun `rejects empty roles`() {
        assertEquals("roles must not be empty", ProductRoles.validate(emptyList()))
    }

    @Test
    fun `accepts known roles`() {
        assertEquals(null, ProductRoles.validate(listOf("admin", "technologist")))
    }
}
