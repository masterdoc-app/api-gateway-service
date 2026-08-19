package pro.masterdoc.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.handle
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

private val warehouseReadFeatures = listOf("warehouse", "equipment", "engineer", "board", "tickets")
private val warehouseIssueFeatures = listOf("engineer", "board", "tickets", "warehouse")
private val warehouseAssetPartsFeatures = listOf("equipment", "admin")

fun Application.installWarehouseRoutes(config: GatewayConfig, deps: GatewayDeps) {
    val client = HttpClient(CIO)
    routing {
        route("/warehouse") {
            route("{tail...}") {
                registerWarehouseProxyHandler(config.warehouseServiceBaseUrl, client, deps)
            }
            registerWarehouseProxyHandler(config.warehouseServiceBaseUrl, client, deps)
        }
    }
}

private fun io.ktor.server.routing.Route.registerWarehouseProxyHandler(
    baseUrl: String,
    client: HttpClient,
    deps: GatewayDeps,
) {
    handle {
        val upstreamUri = call.request.uri.removePrefix("/warehouse").let { path ->
            when {
                path.isEmpty() -> "/"
                path.startsWith("?") -> "/$path"
                else -> path
            }
        }
        val path = upstreamUri.substringBefore('?')
        if (path == "/internal" || path.startsWith("/internal/")) {
            call.respondText("Not Found", status = HttpStatusCode.NotFound)
            return@handle
        }

        val requiredFeatures =
            when {
                call.request.httpMethod == HttpMethod.Get -> warehouseReadFeatures
                call.request.httpMethod == HttpMethod.Post && path in setOf("/parts", "/stock/receipt") ->
                    listOf("warehouse")
                call.request.httpMethod == HttpMethod.Post && path == "/stock/issue" -> warehouseIssueFeatures
                call.request.httpMethod == HttpMethod.Put &&
                    path.startsWith("/assets/") &&
                    path.endsWith("/parts") -> warehouseAssetPartsFeatures
                else -> {
                    call.respondText("Method Not Allowed", status = HttpStatusCode.MethodNotAllowed)
                    return@handle
                }
            }
        if (!call.requireAnyFeature(deps, requiredFeatures)) return@handle
        forward(client, baseUrl, upstreamUri, call, deps)
    }
}
