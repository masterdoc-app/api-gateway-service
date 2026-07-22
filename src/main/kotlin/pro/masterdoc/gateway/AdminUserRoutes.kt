package pro.masterdoc.gateway

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing

fun Application.installAdminUserRoutes(deps: GatewayDeps) {
    routing {
        post("/admin/users/invites") {
            val validated = call.requireUserInvite(deps) ?: return@post
            val orgId = validated.orgId!!
            val request = call.receive<InviteUserRequest>()
            ProductRoles.validate(request.roles)?.let { error ->
                call.respondText(error, status = HttpStatusCode.BadRequest)
                return@post
            }
            try {
                TenantContext.withTenant(orgId) {
                    val user = deps.zitadelAdminClient.inviteUser(request)
                    call.respond(HttpStatusCode.Created, user)
                }
            } catch (e: ZitadelAdminException) {
                call.respondZitadelAdminException(e)
            } catch (_: UpstreamUnavailableException) {
                call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            }
        }

        get("/admin/users") {
            val validated = call.requireUserInvite(deps) ?: return@get
            val orgId = validated.orgId!!
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
            val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            try {
                TenantContext.withTenant(orgId) {
                    val list = deps.zitadelAdminClient.listUsers(limit, offset)
                    call.respond(list)
                }
            } catch (e: ZitadelAdminException) {
                call.respondZitadelAdminException(e)
            } catch (_: UpstreamUnavailableException) {
                call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            }
        }

        put("/admin/users/{id}/roles") {
            val validated = call.requireUserInvite(deps) ?: return@put
            val orgId = validated.orgId!!
            val userId = call.parameters["id"] ?: run {
                call.respondText("Bad Request", status = HttpStatusCode.BadRequest)
                return@put
            }
            val request = call.receive<SetRolesRequest>()
            ProductRoles.validate(request.roles)?.let { error ->
                call.respondText(error, status = HttpStatusCode.BadRequest)
                return@put
            }
            try {
                TenantContext.withTenant(orgId) {
                    val user = deps.zitadelAdminClient.setRoles(userId, request.roles)
                    call.respond(user)
                }
            } catch (e: ZitadelAdminException) {
                call.respondZitadelAdminException(e)
            } catch (_: UpstreamUnavailableException) {
                call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            }
        }

        post("/admin/users/{id}/resend-invite") {
            val validated = call.requireUserInvite(deps) ?: return@post
            val orgId = validated.orgId!!
            val userId = call.parameters["id"] ?: run {
                call.respondText("Bad Request", status = HttpStatusCode.BadRequest)
                return@post
            }
            try {
                TenantContext.withTenant(orgId) {
                    deps.zitadelAdminClient.resendInvite(userId)
                    call.respondText("", status = HttpStatusCode.NoContent)
                }
            } catch (e: ZitadelAdminException) {
                call.respondZitadelAdminException(e)
            } catch (_: UpstreamUnavailableException) {
                call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            }
        }

        delete("/admin/users/{id}") {
            val validated = call.requireUserInvite(deps) ?: return@delete
            val orgId = validated.orgId!!
            val userId = call.parameters["id"] ?: run {
                call.respondText("Bad Request", status = HttpStatusCode.BadRequest)
                return@delete
            }
            try {
                TenantContext.withTenant(orgId) {
                    deps.zitadelAdminClient.deleteUser(userId)
                }
                call.respondText("", status = HttpStatusCode.NoContent)
            } catch (e: ZitadelAdminException) {
                call.respondZitadelAdminException(e)
            } catch (_: UpstreamUnavailableException) {
                call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondZitadelAdminException(e: ZitadelAdminException) {
    val status =
        when (e) {
            is ZitadelAdminException.Conflict -> HttpStatusCode.Conflict
            is ZitadelAdminException.NotFound -> HttpStatusCode.NotFound
            is ZitadelAdminException.BadRequest -> HttpStatusCode.BadRequest
            is ZitadelAdminException.Upstream -> HttpStatusCode.BadGateway
        }
    respondText(e.message ?: "Error", status = status)
}
