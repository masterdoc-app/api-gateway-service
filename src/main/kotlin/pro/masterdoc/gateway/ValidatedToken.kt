package pro.masterdoc.gateway

data class ValidatedToken(
    val subject: String,
    val orgId: String,
)
