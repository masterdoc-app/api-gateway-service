package pro.masterdoc.gateway

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

object TenantContext {
    private val orgId = ThreadLocal<String?>()

    fun current(): String? = orgId.get()

    fun requireOrgId(): String =
        current() ?: error("TenantContext.orgId is not set — call withTenant from authenticated request boundary")

    suspend fun <T> withTenant(orgIdValue: String, block: suspend () -> T): T {
        require(orgIdValue.isNotBlank()) { "orgId must not be blank" }
        return withContext(orgId.asContextElement(orgIdValue)) {
            block()
        }
    }
}
