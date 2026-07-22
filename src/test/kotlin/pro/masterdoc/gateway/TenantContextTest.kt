package pro.masterdoc.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class TenantContextTest {
    @Test
    fun `withTenant sets current and clears after`() = runBlocking {
        assertNull(TenantContext.current())
        TenantContext.withTenant("org-a") {
            assertEquals("org-a", TenantContext.current())
            assertEquals("org-a", TenantContext.requireOrgId())
        }
        assertNull(TenantContext.current())
    }

    @Test
    fun `requireOrgId outside tenant throws`() {
        assertFailsWith<IllegalStateException> {
            TenantContext.requireOrgId()
        }
    }

    @Test
    fun `nested withTenant restores outer`() = runBlocking {
        TenantContext.withTenant("outer") {
            TenantContext.withTenant("inner") {
                assertEquals("inner", TenantContext.requireOrgId())
            }
            assertEquals("outer", TenantContext.requireOrgId())
        }
    }
}
