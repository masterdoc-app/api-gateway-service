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

suspend fun ApplicationCall.requireUserInvite(deps: GatewayDeps): Boolean {
    val authorization = request.header(HttpHeaders.Authorization)
    if (authorization.isNullOrBlank() || !authorization.startsWith("Bearer ")) {
        respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
        return false
    }
    val token = authorization.removePrefix("Bearer ").trim()
    if (token.isEmpty() || deps.tokenValidator.validate(token) == null) {
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
    val features =
        runCatching {
            adminAuthJson
                .parseToJsonElement(String(upstream.body))
                .jsonObject["features"]
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?: emptyList()
        }.getOrDefault(emptyList())
    if ("user_invite" !in features) {
        respondText("Forbidden", status = HttpStatusCode.Forbidden)
        return false
    }
    return true
}
