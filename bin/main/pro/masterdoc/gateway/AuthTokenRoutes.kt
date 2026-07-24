package pro.masterdoc.gateway

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.installAuthTokenRoutes(deps: GatewayDeps) {
    routing {
        post("/auth/token") {
            val formBody = call.receiveText()
            try {
                val upstream = deps.zitadelTokenClient.exchange(formBody)
                val contentType =
                    upstream.contentType?.let { ContentType.parse(it) }
                        ?: ContentType.Application.Json
                call.respondBytes(upstream.body, contentType, upstream.status)
            } catch (_: UpstreamUnavailableException) {
                call.response.header("X-Request-Id", call.request.header("X-Request-Id") ?: "")
                call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            }
        }
    }
}
