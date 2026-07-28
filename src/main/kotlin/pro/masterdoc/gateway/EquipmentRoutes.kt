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
import org.slf4j.LoggerFactory

private val equipmentLog = LoggerFactory.getLogger("pro.masterdoc.gateway.EquipmentRoutes")

fun Application.installEquipmentRoutes(config: GatewayConfig, deps: GatewayDeps) {
    val client = HttpClient(CIO)
    routing {
        proxyPrefix("/sites", config.catalogServiceBaseUrl, client, deps, features = listOf("equipment", "admin"))
        proxyPrefix(
            "/assets",
            config.catalogServiceBaseUrl,
            client,
            deps,
            features = listOf("equipment"),
            scopeFilterHint = true,
        )
        proxyPrefix("/maintenance-maps", config.maintenanceServiceBaseUrl, client, deps, features = listOf("equipment", "charts"))
        proxyPrefix(
            "/work-orders",
            config.dashboardServiceBaseUrl,
            client,
            deps,
            readFeatures = listOf("board", "engineer"),
            writeFeatures = listOf("board"),
            scopeFilterHint = true,
        )
        proxyPrefix(
            "/user-scopes",
            config.catalogServiceBaseUrl,
            client,
            deps,
            features = listOf("board"),
        )
        proxyPrefix("/documents", config.documentServiceBaseUrl, client, deps, features = listOf("equipment"))
        proxyPrefix("/ai/technologist", config.technologistServiceBaseUrl, client, deps, features = listOf("equipment"))
        proxyPrefix("/ai/document-validator", config.technologistServiceBaseUrl, client, deps, features = listOf("equipment"))
        proxyPrefix("/ai/equipment-card", config.technologistServiceBaseUrl, client, deps, features = listOf("equipment"))
        proxyPrefix("/ai/mentor", config.technologistServiceBaseUrl, client, deps, features = listOf("engineer"))
    }
}

private fun io.ktor.server.routing.Routing.proxyPrefix(
    prefix: String,
    baseUrl: String,
    client: HttpClient,
    deps: GatewayDeps,
    features: List<String>? = null,
    readFeatures: List<String>? = null,
    writeFeatures: List<String>? = null,
    scopeFilterHint: Boolean = false,
) {
    require(features != null || (readFeatures != null && writeFeatures != null)) {
        "proxyPrefix requires features or readFeatures+writeFeatures"
    }
    val readGate = readFeatures ?: features!!
    val writeGate = writeFeatures ?: features!!

    fun io.ktor.server.routing.Route.registerProxyHandler() {
        handle {
            val requiredFeatures =
                if (call.request.httpMethod == HttpMethod.Get) readGate else writeGate
            if (!call.requireAnyFeature(deps, requiredFeatures)) return@handle
            forward(client, baseUrl, call.request.uri, call, deps, scopeFilterHint)
        }
    }

    route(prefix) {
        route("{tail...}") {
            registerProxyHandler()
        }
        registerProxyHandler()
    }
}

private suspend fun forward(
    client: HttpClient,
    baseUrl: String,
    uri: String,
    call: io.ktor.server.application.ApplicationCall,
    deps: GatewayDeps,
    scopeFilterHint: Boolean = false,
) {
    val orgId = call.attributes.getOrNull(OrgIdKey) ?: "default-org"
    val userId = call.attributes.getOrNull(UserIdKey) ?: "unknown"
    val callerFeatures = call.attributes.getOrNull(CallerFeaturesKey) ?: emptyList()
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
                if (scopeFilterHint) {
                    header("X-Scope-Filter", scopeFilterHeaderValue(callerFeatures))
                }
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
    } catch (e: Exception) {
        val requestId = call.attributes.getOrNull(RequestIdKey) ?: "-"
        equipmentLog.error(
            "event=proxy_error service=equipment cause=${e.message} requestId=$requestId",
        )
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
        method == HttpMethod.Delete && path.startsWith("/assets/") -> "asset.delete"
        else -> "${method.value.lowercase()}:${path}"
    }
}
