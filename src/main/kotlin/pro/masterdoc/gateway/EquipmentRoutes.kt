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
        proxyPrefix(
            "/sites",
            config.catalogServiceBaseUrl,
            client,
            deps,
            readFeatures = listOf("equipment", "admin", "tickets"),
            writeFeatures = listOf("equipment", "admin"),
        )
        installAssetProxyRoutes(config.catalogServiceBaseUrl, client, deps)
        proxyPrefix("/maintenance-maps", config.maintenanceServiceBaseUrl, client, deps, features = listOf("equipment", "charts"))
        proxyPrefix(
            "/work-orders",
            config.dashboardServiceBaseUrl,
            client,
            deps,
            readFeatures = listOf("board", "engineer", "tickets"),
            writeFeatures = listOf("board", "engineer", "tickets"),
            scopeFilterHint = true,
        )
        proxyPrefix(
            "/reports",
            config.dashboardServiceBaseUrl,
            client,
            deps,
            features = listOf("reports", "admin"),
        )
        installUserScopeProxyRoutes(config.catalogServiceBaseUrl, client, deps)
        proxyPrefix(
            "/engineer-locations",
            config.mapServiceBaseUrl,
            client,
            deps,
            readFeatures = listOf("map"),
            writeFeatures = listOf("engineer"),
        )
        proxyPrefix("/geocode", config.mapServiceBaseUrl, client, deps, features = listOf("admin", "equipment"))
        proxyPrefix("/documents", config.documentServiceBaseUrl, client, deps, features = listOf("equipment"))
        proxyPrefix("/ai/technologist", config.technologistServiceBaseUrl, client, deps, features = listOf("equipment"))
        proxyPrefix("/ai/document-validator", config.technologistServiceBaseUrl, client, deps, features = listOf("equipment"))
        proxyPrefix("/ai/equipment-card", config.technologistServiceBaseUrl, client, deps, features = listOf("equipment"))
        proxyPrefix("/ai/mentor", config.technologistServiceBaseUrl, client, deps, features = listOf("engineer"))
    }
}

/**
 * assets auth:
 * - GET /assets/{id}/qr.pdf: equipment|admin
 * - GET /assets/by-qr/{token}: tickets|engineer|equipment|admin
 * - other GETs: equipment|tickets
 * - other writes: equipment
 */
private fun io.ktor.server.routing.Routing.installAssetProxyRoutes(
    baseUrl: String,
    client: HttpClient,
    deps: GatewayDeps,
) {
    fun pathParts(uri: String): List<String> = uri.substringBefore('?').trim('/').split('/')

    fun isQrPdfPath(uri: String): Boolean {
        val parts = pathParts(uri)
        return parts.size == 3 &&
            parts[0] == "assets" &&
            parts[1].isNotBlank() &&
            parts[2] == "qr.pdf"
    }

    fun isResolveQrPath(uri: String): Boolean {
        val parts = pathParts(uri)
        return parts.size == 3 &&
            parts[0] == "assets" &&
            parts[1] == "by-qr" &&
            parts[2].isNotBlank()
    }

    fun io.ktor.server.routing.Route.registerAssetProxyHandler() {
        handle {
            val method = call.request.httpMethod
            val uri = call.request.uri
            val requiredFeatures =
                when {
                    method == HttpMethod.Get && isResolveQrPath(uri) ->
                        listOf("tickets", "engineer", "equipment", "admin")
                    method == HttpMethod.Get && isQrPdfPath(uri) ->
                        listOf("equipment", "admin")
                    method == HttpMethod.Get -> listOf("equipment", "tickets")
                    else -> listOf("equipment")
                }
            if (!call.requireAnyFeature(deps, requiredFeatures)) return@handle
            forward(
                client,
                baseUrl,
                uri,
                call,
                deps,
                scopeFilterHint = true,
            )
        }
    }

    route("/assets") {
        route("{tail...}") {
            registerAssetProxyHandler()
        }
        registerAssetProxyHandler()
    }
}

/**
 * user-scopes auth:
 * - PUT: admin only
 * - GET /user-scopes/{id}/covers/...: admin|board
 * - GET /user-scopes/{id}: admin|board, or tickets|engineer reading **own** scope
 */
private fun io.ktor.server.routing.Routing.installUserScopeProxyRoutes(
    baseUrl: String,
    client: HttpClient,
    deps: GatewayDeps,
) {
    fun targetUserId(uri: String): String? {
        val path = uri.substringBefore('?')
        val parts = path.trim('/').split('/')
        // user-scopes/{userId}[/{...}]
        if (parts.size < 2 || parts[0] != "user-scopes") return null
        return parts[1].takeIf { it.isNotBlank() }
    }

    fun isCoversPath(uri: String): Boolean {
        val parts = uri.substringBefore('?').trim('/').split('/')
        return parts.size >= 4 && parts[0] == "user-scopes" && parts[2] == "covers"
    }

    route("/user-scopes") {
        route("{tail...}") {
            handle {
                val method = call.request.httpMethod
                val uri = call.request.uri
                val allowed =
                    when {
                        method != HttpMethod.Get && method != HttpMethod.Put -> {
                            call.respondText("Method Not Allowed", status = HttpStatusCode.MethodNotAllowed)
                            false
                        }
                        method == HttpMethod.Put -> call.requireAnyFeature(deps, listOf("admin"))
                        isCoversPath(uri) -> call.requireAnyFeature(deps, listOf("admin", "board"))
                        else -> {
                            // GET own or org-wide (admin/board)
                            if (!call.requireAnyFeature(deps, listOf("admin", "board", "tickets", "engineer"))) {
                                false
                            } else {
                                val features = call.attributes.getOrNull(CallerFeaturesKey).orEmpty()
                                val orgWide = "admin" in features || "board" in features
                                if (orgWide) {
                                    true
                                } else {
                                    val self = call.attributes.getOrNull(UserIdKey)
                                    val target = targetUserId(uri)
                                    if (self != null && target != null && self == target) {
                                        true
                                    } else {
                                        call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
                                        false
                                    }
                                }
                            }
                        }
                    }
                if (!allowed) return@handle
                forward(client, baseUrl, uri, call, deps, scopeFilterHint = false)
            }
        }
        handle {
            call.respondText("Not Found", status = HttpStatusCode.NotFound)
        }
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
    scopeFilterOverride: String? = null,
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
                header("X-Caller-Features", callerFeatures.joinToString(","))
                if (scopeFilterHint) {
                    header("X-Scope-Filter", scopeFilterOverride ?: scopeFilterHeaderValue(callerFeatures))
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
