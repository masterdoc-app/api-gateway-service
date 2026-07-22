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
        proxyPrefix("/assets", config.catalogServiceBaseUrl, client, deps)
        proxyPrefix("/maintenance-maps", config.dashboardServiceBaseUrl, client, deps)
        proxyPrefix("/documents", config.documentServiceBaseUrl, client, deps)
        proxyPrefix("/ai/technologist", config.technologistServiceBaseUrl, client, deps)
    }
}

private fun io.ktor.server.routing.Routing.proxyPrefix(
    prefix: String,
    baseUrl: String,
    client: HttpClient,
    deps: GatewayDeps,
) {
    route(prefix) {
        route("{tail...}") {
            handle {
                if (!call.requireFeature(deps, "equipment")) return@handle
                forward(client, baseUrl, call.request.uri, call)
            }
        }
        handle {
            if (!call.requireFeature(deps, "equipment")) return@handle
            forward(client, baseUrl, call.request.uri, call)
        }
    }
}

private suspend fun forward(
    client: HttpClient,
    baseUrl: String,
    uri: String,
    call: io.ktor.server.application.ApplicationCall,
) {
    val orgId = call.attributes.getOrNull(OrgIdKey) ?: "default-org"
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
                call.request.header(HttpHeaders.ContentType)?.let { header(HttpHeaders.ContentType, it) }
                if (bodyBytes != null) setBody(bodyBytes)
            }
        val contentType =
            upstream.headers[HttpHeaders.ContentType]?.let { ContentType.parse(it) }
                ?: ContentType.Application.Json
        call.respondBytes(upstream.bodyAsBytes(), contentType, upstream.status)
    } catch (_: Exception) {
        call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
    }
}
