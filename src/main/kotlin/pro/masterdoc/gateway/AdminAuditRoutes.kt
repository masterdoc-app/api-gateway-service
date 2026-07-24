package pro.masterdoc.gateway

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.installAdminAuditRoutes(deps: GatewayDeps) {
    routing {
        get("/admin/audit") {
            val validated = call.requireUserInvite(deps) ?: return@get
            val orgId = validated.orgId!!
            val userId = call.request.queryParameters["userId"]?.takeIf { it.isNotBlank() }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
            try {
                val upstream = deps.blackBoxClient.listEvents(orgId = orgId, userId = userId, limit = limit)
                val contentType =
                    upstream.contentType?.let { ContentType.parse(it) } ?: ContentType.Application.Json
                call.respondBytes(upstream.body, contentType, upstream.status)
                if (upstream.status.value in 200..299) {
                    deps.blackBoxClient.recordAsync(
                        CreateAuditEventRequest(
                            orgId = orgId,
                            userId = validated.subject,
                            method = "GET",
                            path = "/admin/audit",
                            status = upstream.status.value,
                            action = "audit.list",
                        ),
                    )
                }
            } catch (_: UpstreamUnavailableException) {
                call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            }
        }
    }
}
