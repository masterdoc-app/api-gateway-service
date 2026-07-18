package pro.masterdoc.gateway

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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

fun Application.installV1ProxyRoutes(deps: GatewayDeps) {
    routing {
        route("/v1") {
            route("{tail...}") {
                handle {
                    val uri = call.request.uri
                    val pathAndQuery =
                        if (uri.startsWith("/v1")) {
                            uri
                        } else {
                            "/v1/" + (call.parameters.getAll("tail")?.joinToString("/") ?: "")
                        }
                    val forwardHeaders =
                        call.request.headers
                            .entries()
                            .filter { (name, _) -> name !in hopByHop }
                            .associate { (name, values) -> name to values.joinToString(",") }
                        val bodyBytes =
                        if (call.request.httpMethod.value in setOf("POST", "PUT", "PATCH")) {
                            call.receiveChannel().readRemaining().readByteArray()
                        } else {
                            null
                        }
                    try {
                        val upstream =
                            deps.backendClient.proxy(
                                method = call.request.httpMethod,
                                pathAndQuery = pathAndQuery,
                                headers = forwardHeaders,
                                body = bodyBytes,
                            )
                        val contentType =
                            upstream.contentType?.let { ContentType.parse(it) }
                                ?: ContentType.Application.Json
                        call.respondBytes(upstream.body, contentType, upstream.status)
                    } catch (_: UpstreamUnavailableException) {
                        call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
                    }
                }
            }
        }
    }
}
