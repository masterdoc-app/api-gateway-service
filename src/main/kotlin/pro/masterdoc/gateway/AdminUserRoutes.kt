package pro.masterdoc.gateway

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val adminUserLog = LoggerFactory.getLogger("pro.masterdoc.gateway.AdminUserRoutes")

fun Application.installAdminUserRoutes(deps: GatewayDeps) {
    routing {
        post("/admin/users/invites") {
            val validated = call.requireAdmin(deps) ?: return@post
            val orgId = validated.orgId!!
            val request = call.receive<InviteUserRequest>()
            if (request.roles.isEmpty()) {
                call.respondText("roles must not be empty", status = HttpStatusCode.BadRequest)
                return@post
            }
            val authorization = call.request.header(io.ktor.http.HttpHeaders.Authorization)!!
            val rolesResult =
                try {
                    deps.featureRolesClient.getRoles(authorization, orgId)
                } catch (e: UpstreamUnavailableException) {
                    call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
                    return@post
                }
            if (!rolesResult.status.isSuccess()) {
                val status = if (rolesResult.status.value >= 500) HttpStatusCode.BadGateway else rolesResult.status
                call.respondText(rolesResult.body.decodeToString(), status = status)
                return@post
            }
            val roles =
                try {
                    Json { ignoreUnknownKeys = true }.decodeFromString<ProductRolesResponse>(rolesResult.body.decodeToString()).items
                } catch (e: Exception) {
                    call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
                    return@post
                }
            val selectedRoles = request.roles.toSet()
            val unknownRole = selectedRoles.firstOrNull { roleId -> roles.none { it.id == roleId } }
            if (unknownRole != null) {
                call.respondText("Unknown role: $unknownRole", status = HttpStatusCode.BadRequest)
                return@post
            }
            val features = roles.filter { it.id in selectedRoles }.flatMap { it.features }.distinct().sorted()
            ProductFeatures.validate(features)?.let { error ->
                call.respondText(error, status = HttpStatusCode.BadRequest)
                return@post
            }
            try {
                TenantContext.withTenant(orgId) {
                    val user =
                        deps.zitadelAdminClient.inviteUser(
                            ResolvedInviteUserRequest(
                                email = request.email,
                                givenName = request.givenName,
                                familyName = request.familyName,
                                features = features,
                            ),
                        )
                    call.respond(HttpStatusCode.Created, user)
                    deps.blackBoxClient.recordAsync(
                        CreateAuditEventRequest(
                            orgId = orgId,
                            userId = validated.subject,
                            method = "POST",
                            path = "/admin/users/invites",
                            status = 201,
                            action = "admin.invite",
                            requestSummary = """{"email":"${request.email}"}""",
                        ),
                    )
                }
            } catch (e: ZitadelAdminException) {
                call.respondZitadelAdminException(e)
            } catch (e: UpstreamUnavailableException) {
                val requestId = call.attributes.getOrNull(RequestIdKey) ?: "-"
                adminUserLog.error(
                    "event=upstream_unavailable service=zitadel cause=${e.message} requestId=$requestId",
                )
                call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            }
        }

        get("/admin/users") {
            val validated = call.requireAnyOfFeatures(deps, listOf("admin", "black_box")) ?: return@get
            val orgId = validated.orgId!!
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
            val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            try {
                TenantContext.withTenant(orgId) {
                    val list = deps.zitadelAdminClient.listUsers(limit, offset)
                    call.respond(list)
                    deps.blackBoxClient.recordAsync(
                        CreateAuditEventRequest(
                            orgId = orgId,
                            userId = validated.subject,
                            method = "GET",
                            path = "/admin/users",
                            status = 200,
                            action = "admin.users.list",
                        ),
                    )
                }
            } catch (e: ZitadelAdminException) {
                call.respondZitadelAdminException(e)
            } catch (e: UpstreamUnavailableException) {
                val requestId = call.attributes.getOrNull(RequestIdKey) ?: "-"
                adminUserLog.error(
                    "event=upstream_unavailable service=zitadel cause=${e.message} requestId=$requestId",
                )
                call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            }
        }

        put("/admin/users/{id}/features") {
            val validated = call.requireAdmin(deps) ?: return@put
            val orgId = validated.orgId!!
            val userId = call.parameters["id"] ?: run {
                call.respondText("Bad Request", status = HttpStatusCode.BadRequest)
                return@put
            }
            val request = call.receive<SetFeaturesRequest>()
            ProductFeatures.validate(request.features)?.let { error ->
                call.respondText(error, status = HttpStatusCode.BadRequest)
                return@put
            }
            try {
                TenantContext.withTenant(orgId) {
                    val user = deps.zitadelAdminClient.setFeatures(userId, request.features)
                    call.respond(user)
                    deps.blackBoxClient.recordAsync(
                        CreateAuditEventRequest(
                            orgId = orgId,
                            userId = validated.subject,
                            method = "PUT",
                            path = "/admin/users/$userId/roles",
                            status = 200,
                            action = "admin.roles.set",
                        ),
                    )
                }
            } catch (e: ZitadelAdminException) {
                call.respondZitadelAdminException(e)
            } catch (e: UpstreamUnavailableException) {
                val requestId = call.attributes.getOrNull(RequestIdKey) ?: "-"
                adminUserLog.error(
                    "event=upstream_unavailable service=zitadel cause=${e.message} requestId=$requestId",
                )
                call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            }
        }

        post("/admin/users/{id}/resend-invite") {
            val validated = call.requireAdmin(deps) ?: return@post
            val orgId = validated.orgId!!
            val userId = call.parameters["id"] ?: run {
                call.respondText("Bad Request", status = HttpStatusCode.BadRequest)
                return@post
            }
            try {
                TenantContext.withTenant(orgId) {
                    deps.zitadelAdminClient.resendInvite(userId)
                    call.respondText("", status = HttpStatusCode.NoContent)
                    deps.blackBoxClient.recordAsync(
                        CreateAuditEventRequest(
                            orgId = orgId,
                            userId = validated.subject,
                            method = "POST",
                            path = "/admin/users/$userId/resend-invite",
                            status = 204,
                            action = "admin.invite.resend",
                        ),
                    )
                }
            } catch (e: ZitadelAdminException) {
                call.respondZitadelAdminException(e)
            } catch (e: UpstreamUnavailableException) {
                val requestId = call.attributes.getOrNull(RequestIdKey) ?: "-"
                adminUserLog.error(
                    "event=upstream_unavailable service=zitadel cause=${e.message} requestId=$requestId",
                )
                call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            }
        }

        delete("/admin/users/{id}") {
            val validated = call.requireAdmin(deps) ?: return@delete
            val orgId = validated.orgId!!
            val userId = call.parameters["id"] ?: run {
                call.respondText("Bad Request", status = HttpStatusCode.BadRequest)
                return@delete
            }
            if (userId == validated.subject) {
                call.respondText("cannot delete yourself", status = HttpStatusCode.Conflict)
                return@delete
            }
            try {
                TenantContext.withTenant(orgId) {
                    deps.zitadelAdminClient.deleteUser(userId)
                }
                call.respondText("", status = HttpStatusCode.NoContent)
                deps.blackBoxClient.recordAsync(
                    CreateAuditEventRequest(
                        orgId = orgId,
                        userId = validated.subject,
                        method = "DELETE",
                        path = "/admin/users/$userId",
                        status = 204,
                        action = "admin.user.delete",
                    ),
                )
            } catch (e: ZitadelAdminException) {
                call.respondZitadelAdminException(e)
            } catch (e: UpstreamUnavailableException) {
                val requestId = call.attributes.getOrNull(RequestIdKey) ?: "-"
                adminUserLog.error(
                    "event=upstream_unavailable service=zitadel cause=${e.message} requestId=$requestId",
                )
                call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondZitadelAdminException(e: ZitadelAdminException) {
    if (e is ZitadelAdminException.Upstream) {
        val requestId = attributes.getOrNull(RequestIdKey) ?: "-"
        adminUserLog.error(
            "event=upstream_error service=zitadel cause=${e.message} requestId=$requestId",
        )
    }
    val status =
        when (e) {
            is ZitadelAdminException.Conflict -> HttpStatusCode.Conflict
            is ZitadelAdminException.NotFound -> HttpStatusCode.NotFound
            is ZitadelAdminException.BadRequest -> HttpStatusCode.BadRequest
            is ZitadelAdminException.Upstream -> HttpStatusCode.BadGateway
        }
    respondText(e.message ?: "Error", status = status)
}
