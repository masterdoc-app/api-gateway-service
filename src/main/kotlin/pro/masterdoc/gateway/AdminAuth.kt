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
import org.slf4j.LoggerFactory

private val adminAuthLog = LoggerFactory.getLogger("pro.masterdoc.gateway.AdminAuth")

private val adminAuthJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

private fun ApplicationCall.requestIdOrNull(): String? = attributes.getOrNull(RequestIdKey)

private fun ApplicationCall.logAuthDenied(reason: String, feature: String? = null) {
    val requestId = requestIdOrNull()
    val msg =
        buildString {
            append("event=auth_denied reason=").append(reason)
            if (requestId != null) append(" requestId=").append(requestId)
            if (feature != null) append(" feature=").append(feature)
        }
    adminAuthLog.warn(msg)
}

private fun ApplicationCall.logUpstreamUnavailable(service: String, cause: Throwable) {
    val requestId = requestIdOrNull()
    adminAuthLog.error(
        "event=upstream_unavailable service=$service cause=${cause.message} requestId=${requestId ?: "-"}",
    )
}

private fun ApplicationCall.logUpstreamError(service: String, status: Int) {
    val requestId = requestIdOrNull()
    adminAuthLog.warn(
        "event=upstream_error service=$service status=$status requestId=${requestId ?: "-"}",
    )
}

suspend fun ApplicationCall.requireAuthenticated(deps: GatewayDeps): ValidatedToken? {
    val authorization = request.header(HttpHeaders.Authorization)
    if (authorization.isNullOrBlank() || !authorization.startsWith("Bearer ")) {
        logAuthDenied("missing_bearer")
        respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
        return null
    }
    val token = authorization.removePrefix("Bearer ").trim()
    val validated = if (token.isEmpty()) null else deps.tokenValidator.validate(token)
    if (validated == null) {
        logAuthDenied("invalid_token")
        respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
        return null
    }
    if (validated.orgId.isNullOrBlank()) {
        logAuthDenied("missing_org")
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
        } catch (e: UpstreamUnavailableException) {
            logUpstreamUnavailable("feature-service", e)
            respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            return null
        }
    if (upstream.status != HttpStatusCode.OK) {
        logUpstreamError("feature-service", upstream.status.value)
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
        logAuthDenied("forbidden", feature = "admin")
        respondText("Forbidden", status = HttpStatusCode.Forbidden)
        return null
    }
    return validated
}

/** Like [requireAdmin], but succeeds when any of [allowed] is in the caller's features. */
suspend fun ApplicationCall.requireAnyOfFeatures(
    deps: GatewayDeps,
    allowed: List<String>,
): ValidatedToken? {
    val validated = requireAuthenticated(deps) ?: return null
    val authorization = request.header(HttpHeaders.Authorization)!!
    val upstream =
        try {
            deps.featureClient.getMe(authorization)
        } catch (e: UpstreamUnavailableException) {
            logUpstreamUnavailable("feature-service", e)
            respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            return null
        }
    if (upstream.status != HttpStatusCode.OK) {
        logUpstreamError("feature-service", upstream.status.value)
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
    if (allowed.none { it in features }) {
        logAuthDenied("forbidden", feature = allowed.joinToString(","))
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
        logAuthDenied("missing_bearer")
        respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
        return false
    }
    val token = authorization.removePrefix("Bearer ").trim()
    val validated = if (token.isEmpty()) null else deps.tokenValidator.validate(token)
    if (validated == null) {
        logAuthDenied("invalid_token")
        respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
        return false
    }
    val upstream =
        try {
            deps.featureClient.getMe(authorization)
        } catch (e: UpstreamUnavailableException) {
            logUpstreamUnavailable("feature-service", e)
            respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            return false
        }
    if (upstream.status != HttpStatusCode.OK) {
        logUpstreamError("feature-service", upstream.status.value)
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
        logAuthDenied("forbidden", feature = features.joinToString(","))
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
