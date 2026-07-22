package pro.masterdoc.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class ZitadelAdminOrgHeaderTest {
    @Test
    fun `resolveOrgHeader reads TenantContext`() = runBlocking {
        TenantContext.withTenant("org-xyz") {
            assertEquals("org-xyz", ZitadelAdminOrgHeader.resolve())
        }
    }
}
