package pro.masterdoc.gateway

object OrgClaimExtractor {
    /** Present when auth requested scope `urn:zitadel:iam:org:id:{id}`. */
    const val ORG_ID_CLAIM = "urn:zitadel:iam:org:id"

    /** Present when auth requested scope `urn:zitadel:iam:user:resourceowner` (home org). */
    const val RESOURCE_OWNER_ID_CLAIM = "urn:zitadel:iam:user:resourceowner:id"

    fun orgIdFrom(claims: Map<String, Any?>): String? {
        nonBlank(claims[ORG_ID_CLAIM])?.let { return it }
        return nonBlank(claims[RESOURCE_OWNER_ID_CLAIM])
    }

    private fun nonBlank(raw: Any?): String? {
        val value = raw?.toString()?.trim().orEmpty()
        return value.takeIf { it.isNotEmpty() }
    }
}
