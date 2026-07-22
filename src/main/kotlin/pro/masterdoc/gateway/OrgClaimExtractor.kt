package pro.masterdoc.gateway

object OrgClaimExtractor {
    const val ORG_ID_CLAIM = "urn:zitadel:iam:org:id"

    fun orgIdFrom(claims: Map<String, Any?>): String? {
        val raw = claims[ORG_ID_CLAIM] ?: return null
        val value = raw.toString().trim()
        return value.takeIf { it.isNotEmpty() }
    }
}
