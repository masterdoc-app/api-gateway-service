package pro.masterdoc.gateway

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

fun Application.installAdminRoleRoutes(deps: GatewayDeps) {
    routing {
        get("/admin/roles") {
            val validated = call.requireAdmin(deps) ?: return@get
            val authorization = call.request.header(HttpHeaders.Authorization)!!
            try {
                val upstream = deps.featureRolesClient.getRoles(authorization, validated.orgId!!)
                call.respondFeatureRoles(upstream)
            } catch (e: UpstreamUnavailableException) {
                call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            }
        }

        put("/admin/roles/{id}") {
            val validated = call.requireAdmin(deps) ?: return@put
            val roleId = call.parameters["id"]
            if (roleId.isNullOrBlank()) {
                call.respondText("Bad Request", status = HttpStatusCode.BadRequest)
                return@put
            }
            val authorization = call.request.header(HttpHeaders.Authorization)!!
            val body = call.receiveChannel().readRemaining().readByteArray()
            try {
                val upstream =
                    deps.featureRolesClient.updateRole(
                        authorization,
                        validated.orgId!!,
                        roleId,
                        body,
                        call.request.header(HttpHeaders.ContentType),
                    )
                call.respondFeatureRoles(upstream)
            } catch (e: UpstreamUnavailableException) {
                call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondFeatureRoles(upstream: UpstreamResult) {
    val contentType =
        upstream.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
            ?: ContentType.Application.Json
    respondBytes(upstream.body, contentType, upstream.status)
}
