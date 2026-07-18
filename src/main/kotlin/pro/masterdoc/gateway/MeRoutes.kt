package pro.masterdoc.gateway

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.installMeRoutes(deps: GatewayDeps) {
    routing {
        get("/me") {
            val authorization = call.request.header(HttpHeaders.Authorization)
            if (authorization.isNullOrBlank() || !authorization.startsWith("Bearer ")) {
                call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                return@get
            }
            val token = authorization.removePrefix("Bearer ").trim()
            if (token.isEmpty() || deps.tokenValidator.validate(token) == null) {
                call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                return@get
            }
            try {
                val upstream = deps.featureClient.getMe(authorization)
                val contentType =
                    upstream.contentType?.let { ContentType.parse(it) } ?: ContentType.Application.Json
                call.respondBytes(upstream.body, contentType, upstream.status)
            } catch (_: UpstreamUnavailableException) {
                call.response.header("X-Request-Id", call.request.header("X-Request-Id") ?: "")
                call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            }
        }
    }
}
