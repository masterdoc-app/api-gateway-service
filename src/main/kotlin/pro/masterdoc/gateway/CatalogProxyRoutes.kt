package pro.masterdoc.gateway

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

private val hopByHop =
    setOf(
        HttpHeaders.Host,
        HttpHeaders.ContentLength,
        HttpHeaders.TransferEncoding,
        HttpHeaders.Connection,
        "Keep-Alive",
        "Proxy-Authenticate",
        "Proxy-Authorization",
        "TE",
        "Trailers",
        "Upgrade",
    )

fun Application.installCatalogProxyRoutes(deps: GatewayDeps) {
    routing {
        route("/sites") {
            route("{tail...}") {
                handle { proxyCatalog(call, deps) }
            }
            handle { proxyCatalog(call, deps) }
        }
        route("/assets") {
            route("{tail...}") {
                handle { proxyCatalog(call, deps) }
            }
            handle { proxyCatalog(call, deps) }
        }
    }
}

private suspend fun proxyCatalog(
    call: io.ktor.server.application.ApplicationCall,
    deps: GatewayDeps,
) {
    val validated = call.requireAuthenticated(deps) ?: return
    val orgId = validated.orgId!!
    val pathAndQuery = call.request.uri
    val bodyBytes =
        if (call.request.httpMethod.value in setOf("POST", "PUT", "PATCH")) {
            call.receiveChannel().readRemaining().readByteArray()
        } else {
            null
        }
    val forwardHeaders =
        call.request.headers
            .entries()
            .filter { (name, _) -> name !in hopByHop && !name.equals("X-Org-Id", ignoreCase = true) }
            .associate { (name, values) -> name to values.joinToString(",") }
            .toMutableMap()
    forwardHeaders["X-Org-Id"] = orgId
    try {
        val upstream =
            deps.catalogClient.proxy(
                method = call.request.httpMethod,
                pathAndQuery = pathAndQuery,
                headers = forwardHeaders,
                body = bodyBytes,
            )
        val contentType =
            upstream.contentType?.let { ContentType.parse(it) } ?: ContentType.Application.Json
        call.respondBytes(upstream.body, contentType, upstream.status)
        if (upstream.status.value in 200..299) {
            deps.blackBoxClient.recordAsync(
                CreateAuditEventRequest(
                    orgId = orgId,
                    userId = validated.subject,
                    method = call.request.httpMethod.value,
                    path = pathAndQuery.substringBefore('?'),
                    status = upstream.status.value,
                    action = catalogAction(call.request.httpMethod, pathAndQuery),
                    requestSummary = summarizeBody(bodyBytes),
                    responseSummary = summarizeBody(upstream.body),
                ),
            )
        }
    } catch (_: UpstreamUnavailableException) {
        call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
    }
}

private fun catalogAction(method: HttpMethod, pathAndQuery: String): String {
    val path = pathAndQuery.substringBefore('?')
    return when {
        method == HttpMethod.Post && path == "/sites" -> "site.create"
        method == HttpMethod.Put || method == HttpMethod.Patch -> "site.update"
        method == HttpMethod.Delete && path.startsWith("/sites/") -> "site.delete"
        method == HttpMethod.Post && path == "/assets" -> "asset.create"
        method == HttpMethod.Post && path.endsWith("/move") -> "asset.move"
        method == HttpMethod.Post && path.endsWith("/confirm") -> "asset.confirm"
        method == HttpMethod.Post && path.endsWith("/reject") -> "asset.reject"
        else -> "${method.value.lowercase()}:${path}"
    }
}
