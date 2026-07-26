package pro.masterdoc.gateway

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val adminAuthJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

suspend fun ApplicationCall.requireAuthenticated(deps: GatewayDeps): ValidatedToken? {
    val authorization = request.header(HttpHeaders.Authorization)
    if (authorization.isNullOrBlank() || !authorization.startsWith("Bearer ")) {
        respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
        return null
    }
    val token = authorization.removePrefix("Bearer ").trim()
    val validated = if (token.isEmpty()) null else deps.tokenValidator.validate(token)
    if (validated == null) {
        respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
        return null
    }
    if (validated.orgId.isNullOrBlank()) {
        respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
        return null
    }
    return validated
}

suspend fun ApplicationCall.requireAdmin(deps: GatewayDeps): ValidatedToken? {
    val validated = requireAuthenticated(deps) ?: return null
    val authorization = request.header(HttpHeaders.Authorization)!!
    val upstream =
        try {
            deps.featureClient.getMe(authorization)
        } catch (_: UpstreamUnavailableException) {
            respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            return null
        }
    if (upstream.status != HttpStatusCode.OK) {
        respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
        return null
    }
    val features =
        runCatching {
            adminAuthJson
                .parseToJsonElement(String(upstream.body))
                .jsonObject["features"]
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?: emptyList()
        }.getOrDefault(emptyList())
    if ("admin" !in features) {
        respondText("Forbidden", status = HttpStatusCode.Forbidden)
        return null
    }
    return validated
}

suspend fun ApplicationCall.requireFeature(deps: GatewayDeps, feature: String): Boolean =
    requireAnyFeature(deps, listOf(feature))

suspend fun ApplicationCall.requireAnyFeature(deps: GatewayDeps, features: List<String>): Boolean {
    val authorization = request.header(HttpHeaders.Authorization)
    if (authorization.isNullOrBlank() || !authorization.startsWith("Bearer ")) {
        respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
        return false
    }
    val token = authorization.removePrefix("Bearer ").trim()
    val validated = if (token.isEmpty()) null else deps.tokenValidator.validate(token)
    if (validated == null) {
        respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
        return false
    }
    val upstream =
        try {
            deps.featureClient.getMe(authorization)
        } catch (_: UpstreamUnavailableException) {
            respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            return false
        }
    if (upstream.status != HttpStatusCode.OK) {
        respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
        return false
    }
    val granted =
        runCatching {
            adminAuthJson
                .parseToJsonElement(String(upstream.body))
                .jsonObject["features"]
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?: emptyList()
        }.getOrDefault(emptyList())
    if (features.none { it in granted }) {
        respondText("Forbidden", status = HttpStatusCode.Forbidden)
        return false
    }
    attributes.put(OrgIdKey, validated.orgId?.takeIf { it.isNotBlank() } ?: "default-org")
    attributes.put(UserIdKey, validated.subject)
    attributes.put(AuthHeaderKey, authorization)
    return true
}

val OrgIdKey = io.ktor.util.AttributeKey<String>("orgId")
val UserIdKey = io.ktor.util.AttributeKey<String>("userId")
val AuthHeaderKey = io.ktor.util.AttributeKey<String>("authHeader")
