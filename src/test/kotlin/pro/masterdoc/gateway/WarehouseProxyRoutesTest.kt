package pro.masterdoc.gateway

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WarehouseProxyRoutesTest {
    private val json = Json

    private fun featureClientWith(vararg features: String): FeatureServiceClient =
        FeatureServiceClient {
            UpstreamResult(
                HttpStatusCode.OK,
                "application/json",
                """{"userInfo":{"id":"user-1"},"features":${json.encodeToString(features.toList())}}""".toByteArray(),
            )
        }

    @Test
    fun `warehouse parts read strips prefix and forwards caller headers`() {
        var orgId: String? = null
        var userId: String? = null
        val warehouseServer =
            jsonServer("/parts") { headers ->
                orgId = headers.getFirst("X-Org-Id")
                userId = headers.getFirst("X-User-Id")
            }

        try {
            testApplication {
                application {
                    module(
                        GatewayConfig.testDefaults().copy(
                            warehouseServiceBaseUrl = "http://127.0.0.1:${warehouseServer.address.port}",
                        ),
                        GatewayDeps(
                            featureClient = featureClientWith("warehouse"),
                            backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                            tokenValidator = TokenValidator.accepting(),
                        ),
                    )
                }

                val response =
                    client.get("/warehouse/parts") {
                        header(HttpHeaders.Authorization, "Bearer good")
                    }

                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("test-org", orgId)
                assertEquals("test-sub", userId)
            }
        } finally {
            warehouseServer.stop(0)
        }
    }

    @Test
    fun `warehouse receipt requires warehouse feature`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = featureClientWith("engineer"),
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }

        val response =
            client.post("/warehouse/stock/receipt") {
                header(HttpHeaders.Authorization, "Bearer good")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `internal warehouse advice tick is not exposed`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = featureClientWith("warehouse"),
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }

        val response =
            client.post("/warehouse/internal/advice/tick") {
                header(HttpHeaders.Authorization, "Bearer good")
            }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `engineer can issue warehouse stock`() {
        val warehouseServer = jsonServer("/stock/issue")

        try {
            testApplication {
                application {
                    module(
                        GatewayConfig.testDefaults().copy(
                            warehouseServiceBaseUrl = "http://127.0.0.1:${warehouseServer.address.port}",
                        ),
                        GatewayDeps(
                            featureClient = featureClientWith("engineer"),
                            backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                            tokenValidator = TokenValidator.accepting(),
                        ),
                    )
                }

                val response =
                    client.post("/warehouse/stock/issue") {
                        header(HttpHeaders.Authorization, "Bearer good")
                    }

                assertEquals(HttpStatusCode.OK, response.status)
            }
        } finally {
            warehouseServer.stop(0)
        }
    }

    @Test
    fun `equipment can update asset part links`() {
        val warehouseServer = jsonServer("/assets/asset-1/parts")

        try {
            testApplication {
                application {
                    module(
                        GatewayConfig.testDefaults().copy(
                            warehouseServiceBaseUrl = "http://127.0.0.1:${warehouseServer.address.port}",
                        ),
                        GatewayDeps(
                            featureClient = featureClientWith("equipment"),
                            backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                            tokenValidator = TokenValidator.accepting(),
                        ),
                    )
                }

                val response =
                    client.put("/warehouse/assets/asset-1/parts") {
                        header(HttpHeaders.Authorization, "Bearer good")
                    }

                assertEquals(HttpStatusCode.OK, response.status)
            }
        } finally {
            warehouseServer.stop(0)
        }
    }

    private fun jsonServer(
        path: String,
        inspectHeaders: (com.sun.net.httpserver.Headers) -> Unit = {},
    ): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext(path) { exchange ->
                inspectHeaders(exchange.requestHeaders)
                val body = """{"ok":true}""".toByteArray()
                exchange.responseHeaders.add(HttpHeaders.ContentType, "application/json")
                exchange.sendResponseHeaders(HttpStatusCode.OK.value, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
}
