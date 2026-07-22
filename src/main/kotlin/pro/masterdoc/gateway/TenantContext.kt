package pro.masterdoc.gateway

object TenantContext {
    private val orgId = ThreadLocal<String?>()

    fun current(): String? = orgId.get()

    fun requireOrgId(): String =
        current() ?: error("TenantContext.orgId is not set — call withTenant from authenticated request boundary")

    suspend fun <T> withTenant(orgIdValue: String, block: suspend () -> T): T {
        require(orgIdValue.isNotBlank()) { "orgId must not be blank" }
        val previous = orgId.get()
        orgId.set(orgIdValue)
        return try {
            block()
        } finally {
            if (previous == null) orgId.remove() else orgId.set(previous)
        }
    }
}
