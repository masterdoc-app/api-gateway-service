package pro.masterdoc.gateway

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val clientEventsJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

@Serializable
data class ClientEventRequest(
    val action: String,
    val path: String? = null,
    val props: Map<String, String> = emptyMap(),
)

fun Application.installClientEventsRoutes(deps: GatewayDeps) {
    routing {
        post("/client-events") {
            val validated = call.requireAuthenticated(deps) ?: return@post
            val body =
                try {
                    call.receive<ClientEventRequest>()
                } catch (_: Exception) {
                    call.respondText("Bad Request", status = HttpStatusCode.BadRequest)
                    return@post
                }
            if (body.action.isBlank()) {
                call.respondText("Bad Request", status = HttpStatusCode.BadRequest)
                return@post
            }
            val orgId = validated.orgId!!
            val path = body.path?.takeIf { it.isNotBlank() } ?: "ui"
            val summary =
                if (body.props.isEmpty()) {
                    null
                } else {
                    clientEventsJson.encodeToString(body.props)
                }
            deps.blackBoxClient.recordAsync(
                CreateAuditEventRequest(
                    orgId = orgId,
                    userId = validated.subject,
                    method = "UI",
                    path = path,
                    status = 200,
                    action = body.action.trim(),
                    requestSummary = summary,
                ),
            )
            call.respond(HttpStatusCode.Accepted)
        }
    }
}
