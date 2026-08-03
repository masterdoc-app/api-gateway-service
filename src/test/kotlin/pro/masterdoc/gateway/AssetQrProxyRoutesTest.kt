package pro.masterdoc.gateway

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AssetQrProxyRoutesTest {
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
    fun `tickets cannot generate asset qr`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = featureClientWith("tickets"),
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }

        val response =
            client.post("/assets/asset-1/qr") {
                header(HttpHeaders.Authorization, "Bearer good")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `equipment write gate proxies legacy asset qr post without special handling`() {
        val catalogServer = jsonServer("/assets/asset-1/qr")

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
                    client.post("/assets/asset-1/qr") {
                        header(HttpHeaders.Authorization, "Bearer good")
                    }

                assertEquals(HttpStatusCode.OK, response.status)
            }
        } finally {
            catalogServer.stop(0)
        }
    }

    @Test
    fun `admin without equipment cannot write asset qr post`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = featureClientWith("admin"),
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }

        val response =
            client.post("/assets/asset-1/qr") {
                header(HttpHeaders.Authorization, "Bearer good")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `removed asset qr feature cannot authorize writes`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = featureClientWith("asset_qr"),
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }

        val response =
            client.post("/assets/asset-1/qr") {
                header(HttpHeaders.Authorization, "Bearer good")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `tickets cannot download asset qr pdf`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = featureClientWith("tickets"),
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }

        val response =
            client.get("/assets/asset-1/qr.pdf") {
                header(HttpHeaders.Authorization, "Bearer good")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `equipment and admin can download asset qr pdf`() {
        val catalogServer = jsonServer("/assets/asset-1/qr.pdf")

        try {
            listOf("equipment", "admin").forEach { feature ->
                testApplication {
                    application {
                        module(
                            GatewayConfig.testDefaults().copy(
                                catalogServiceBaseUrl = "http://127.0.0.1:${catalogServer.address.port}",
                            ),
                            GatewayDeps(
                                featureClient = featureClientWith(feature),
                                backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                                tokenValidator = TokenValidator.accepting(),
                            ),
                        )
                    }

                    val response =
                        client.get("/assets/asset-1/qr.pdf") {
                            header(HttpHeaders.Authorization, "Bearer good")
                        }

                    assertEquals(HttpStatusCode.OK, response.status, feature)
                }
            }
        } finally {
            catalogServer.stop(0)
        }
    }

    @Test
    fun `tickets can resolve asset qr with scope filter`() {
        var scopeFilter: String? = null
        val catalogServer = jsonServer("/assets/by-qr/token-1") { headers ->
            scopeFilter = headers.getFirst("X-Scope-Filter")
        }

        try {
            testApplication {
                application {
                    module(
                        GatewayConfig.testDefaults().copy(
                            catalogServiceBaseUrl = "http://127.0.0.1:${catalogServer.address.port}",
                        ),
                        GatewayDeps(
                            featureClient = featureClientWith("tickets"),
                            backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                            tokenValidator = TokenValidator.accepting(),
                        ),
                    )
                }

                val response =
                    client.get("/assets/by-qr/token-1") {
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
    fun `engineer equipment and admin can resolve asset qr`() {
        val catalogServer = jsonServer("/assets/by-qr/token-1")

        try {
            listOf("engineer", "equipment", "admin").forEach { feature ->
                testApplication {
                    application {
                        module(
                            GatewayConfig.testDefaults().copy(
                                catalogServiceBaseUrl = "http://127.0.0.1:${catalogServer.address.port}",
                            ),
                            GatewayDeps(
                                featureClient = featureClientWith(feature),
                                backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                                tokenValidator = TokenValidator.accepting(),
                            ),
                        )
                    }

                    val response =
                        client.get("/assets/by-qr/token-1") {
                            header(HttpHeaders.Authorization, "Bearer good")
                        }

                    assertEquals(HttpStatusCode.OK, response.status, feature)
                }
            }
        } finally {
            catalogServer.stop(0)
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
