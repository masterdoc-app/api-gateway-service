package pro.masterdoc.gateway

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

private val aiMessageRoutesLog = LoggerFactory.getLogger("pro.masterdoc.gateway.AiMessageRoutes")

fun Application.installAiMessageRoutes(deps: GatewayDeps) {
    routing {
        get("/ai-messages") {
            val validated = call.requireAnyOfFeatures(deps, listOf("ai")) ?: return@get
            val orgId = validated.orgId!!
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 30
            val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            try {
                val upstream = deps.aiMessageClient.listMessages(orgId = orgId, limit = limit, offset = offset)
                val contentType =
                    upstream.contentType?.let { ContentType.parse(it) } ?: ContentType.Application.Json
                call.respondBytes(upstream.body, contentType, upstream.status)
            } catch (e: UpstreamUnavailableException) {
                val requestId = call.attributes.getOrNull(RequestIdKey) ?: "-"
                aiMessageRoutesLog.error(
                    "event=upstream_unavailable service=ai-message-service cause=${e.message} requestId=$requestId",
                )
                call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            }
        }
    }
}
