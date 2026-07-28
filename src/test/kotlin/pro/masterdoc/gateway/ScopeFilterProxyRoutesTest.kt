package pro.masterdoc.gateway

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ScopeFilterProxyRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun featureClientWith(vararg features: String): FeatureServiceClient =
        FeatureServiceClient {
            UpstreamResult(
                HttpStatusCode.OK,
                "application/json",
                """{"userInfo":{"id":"a"},"features":${json.encodeToString(features.toList())}}""".toByteArray(),
            )
        }

    @Test
    fun `GET assets forwards X-Scope-Filter 1 for engineer-like caller`() {
        var scopeFilter: String? = null
        val catalogServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        catalogServer.createContext("/assets") { exchange ->
            scopeFilter = exchange.requestHeaders.getFirst("X-Scope-Filter")
            val body = """{"items":[]}""".toByteArray()
            exchange.responseHeaders.add(HttpHeaders.ContentType, "application/json")
            exchange.sendResponseHeaders(HttpStatusCode.OK.value, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        catalogServer.start()

        try {
            testApplication {
                application {
                    module(
                        GatewayConfig.testDefaults().copy(
                            catalogServiceBaseUrl = "http://127.0.0.1:${catalogServer.address.port}",
                        ),
                        GatewayDeps(
                            featureClient = featureClientWith("equipment"),
                            backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                            tokenValidator = TokenValidator.accepting(),
                        ),
                    )
                }

                val response =
                    client.get("/assets") {
                        header(HttpHeaders.Authorization, "Bearer good")
                    }

                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("1", scopeFilter)
            }
        } finally {
            catalogServer.stop(0)
        }
    }

    @Test
    fun `GET assets forwards X-Scope-Filter 0 for board caller`() {
        var scopeFilter: String? = null
        val catalogServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        catalogServer.createContext("/assets") { exchange ->
            scopeFilter = exchange.requestHeaders.getFirst("X-Scope-Filter")
            val body = """{"items":[]}""".toByteArray()
            exchange.responseHeaders.add(HttpHeaders.ContentType, "application/json")
            exchange.sendResponseHeaders(HttpStatusCode.OK.value, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        catalogServer.start()

        try {
            testApplication {
                application {
                    module(
                        GatewayConfig.testDefaults().copy(
                            catalogServiceBaseUrl = "http://127.0.0.1:${catalogServer.address.port}",
                        ),
                        GatewayDeps(
                            featureClient = featureClientWith("board", "equipment"),
                            backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                            tokenValidator = TokenValidator.accepting(),
                        ),
                    )
                }

                val response =
                    client.get("/assets") {
                        header(HttpHeaders.Authorization, "Bearer good")
                    }

                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("0", scopeFilter)
            }
        } finally {
            catalogServer.stop(0)
        }
    }

    @Test
    fun `GET work-orders forwards X-Scope-Filter 1 for engineer caller`() {
        var scopeFilter: String? = null
        val dashboardServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        dashboardServer.createContext("/work-orders/board") { exchange ->
            scopeFilter = exchange.requestHeaders.getFirst("X-Scope-Filter")
            val body = """{"items":[]}""".toByteArray()
            exchange.responseHeaders.add(HttpHeaders.ContentType, "application/json")
            exchange.sendResponseHeaders(HttpStatusCode.OK.value, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        dashboardServer.start()

        try {
            testApplication {
                application {
                    module(
                        GatewayConfig.testDefaults().copy(
                            dashboardServiceBaseUrl = "http://127.0.0.1:${dashboardServer.address.port}",
                        ),
                        GatewayDeps(
                            featureClient = featureClientWith("engineer"),
                            backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                            tokenValidator = TokenValidator.accepting(),
                        ),
                    )
                }

                val response =
                    client.get("/work-orders/board") {
                        header(HttpHeaders.Authorization, "Bearer good")
                    }

                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("1", scopeFilter)
            }
        } finally {
            dashboardServer.stop(0)
        }
    }
}
