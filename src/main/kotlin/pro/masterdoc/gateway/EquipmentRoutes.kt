package pro.masterdoc.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

fun Application.installEquipmentRoutes(config: GatewayConfig, deps: GatewayDeps) {
    val client = HttpClient(CIO)
    routing {
        proxyPrefix("/sites", config.catalogServiceBaseUrl, client, deps, features = listOf("equipment", "user_invite"))
        proxyPrefix("/assets", config.catalogServiceBaseUrl, client, deps, features = listOf("equipment"))
        proxyPrefix("/maintenance-maps", config.dashboardServiceBaseUrl, client, deps, features = listOf("equipment", "charts"))
        proxyPrefix("/work-orders", config.dashboardServiceBaseUrl, client, deps, features = listOf("board"))
        proxyPrefix("/documents", config.documentServiceBaseUrl, client, deps, features = listOf("equipment"))
        proxyPrefix("/ai/technologist", config.technologistServiceBaseUrl, client, deps, features = listOf("equipment"))
    }
}

private fun io.ktor.server.routing.Routing.proxyPrefix(
    prefix: String,
    baseUrl: String,
    client: HttpClient,
    deps: GatewayDeps,
    features: List<String>,
) {
    route(prefix) {
        route("{tail...}") {
            handle {
                if (!call.requireAnyFeature(deps, features)) return@handle
                forward(client, baseUrl, call.request.uri, call, deps)
            }
        }
        handle {
            if (!call.requireAnyFeature(deps, features)) return@handle
            forward(client, baseUrl, call.request.uri, call, deps)
        }
    }
}

private suspend fun forward(
    client: HttpClient,
    baseUrl: String,
    uri: String,
    call: io.ktor.server.application.ApplicationCall,
    deps: GatewayDeps,
) {
    val orgId = call.attributes.getOrNull(OrgIdKey) ?: "default-org"
    val userId = call.attributes.getOrNull(UserIdKey) ?: "unknown"
    val method = call.request.httpMethod
    val bodyBytes =
        if (method in setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch)) {
            call.receiveChannel().readRemaining().readByteArray()
        } else {
            null
        }
    try {
        val upstream =
            client.request("$baseUrl$uri") {
                this.method = method
                header("X-Org-Id", orgId)
                header("X-User-Id", userId)
                if (bodyBytes != null) {
                    val contentTypeHeader = call.request.header(HttpHeaders.ContentType)
                    val contentType =
                        contentTypeHeader?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                            ?: ContentType.Application.OctetStream
                    setBody(ByteArrayContent(bodyBytes, contentType))
                }
            }
        val responseBody = upstream.bodyAsBytes()
        val contentType =
            upstream.headers[HttpHeaders.ContentType]?.let { ContentType.parse(it) }
                ?: ContentType.Application.Json
        call.respondBytes(responseBody, contentType, upstream.status)
        if (upstream.status.value in 200..299) {
            deps.blackBoxClient.recordAsync(
                CreateAuditEventRequest(
                    orgId = orgId,
                    userId = userId,
                    method = method.value,
                    path = uri.substringBefore('?'),
                    status = upstream.status.value,
                    action = equipmentAction(method, uri),
                    requestSummary = summarizeBody(bodyBytes),
                    responseSummary = summarizeBody(responseBody),
                ),
            )
        }
    } catch (_: Exception) {
        call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
    }
}

private fun equipmentAction(method: HttpMethod, uri: String): String {
    val path = uri.substringBefore('?')
    return when {
        method == HttpMethod.Post && path == "/sites" -> "site.create"
        (method == HttpMethod.Put || method == HttpMethod.Patch) && path.startsWith("/sites/") -> "site.update"
        method == HttpMethod.Delete && path.startsWith("/sites/") -> "site.delete"
        method == HttpMethod.Post && path == "/assets" -> "asset.create"
        method == HttpMethod.Post && path.endsWith("/move") -> "asset.move"
        method == HttpMethod.Post && path.endsWith("/confirm") -> "asset.confirm"
        method == HttpMethod.Post && path.endsWith("/reject") -> "asset.reject"
        else -> "${method.value.lowercase()}:${path}"
    }
}
