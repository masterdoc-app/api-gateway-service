package pro.masterdoc.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    @Test
    fun `concurrent withTenant does not interleave org ids`() = runBlocking {
        val results = mutableListOf<Pair<String, String>>()
        val mutex = Mutex()
        coroutineScope {
            listOf(
                    async {
                        TenantContext.withTenant("org-a") {
                            delay(20)
                            mutex.withLock { results.add("a" to TenantContext.requireOrgId()) }
                        }
                    },
                    async {
                        TenantContext.withTenant("org-b") {
                            delay(5)
                            mutex.withLock { results.add("b" to TenantContext.requireOrgId()) }
                        }
                    },
                )
                .awaitAll()
        }
        assertEquals("org-a", results.first { it.first == "a" }.second)
        assertEquals("org-b", results.first { it.first == "b" }.second)
    }
}
