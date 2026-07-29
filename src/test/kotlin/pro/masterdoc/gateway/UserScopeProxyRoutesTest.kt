package pro.masterdoc.gateway

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class UserScopeProxyRoutesTest {
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
    fun `admin can GET and PUT user-scopes`() {
        val catalogServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        catalogServer.createContext("/user-scopes/user-1") { exchange ->
            val body =
                if (exchange.requestMethod == "GET") {
                    """{"siteIds":["site-1"],"assetIds":[]}""".toByteArray()
                } else {
                    """{"siteIds":[],"assetIds":[]}""".toByteArray()
                }
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
                            featureClient = featureClientWith("admin"),
                            backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                            tokenValidator = TokenValidator.accepting(),
                        ),
                    )
                }

                val getResponse =
                    client.get("/user-scopes/user-1") {
                        header(HttpHeaders.Authorization, "Bearer good")
                    }
                assertEquals(HttpStatusCode.OK, getResponse.status)
                assertTrue(getResponse.bodyAsText().contains("site-1"))

                val putResponse =
                    client.put("/user-scopes/user-1") {
                        header(HttpHeaders.Authorization, "Bearer good")
                        contentType(ContentType.Application.Json)
                        setBody("""{"siteIds":[],"assetIds":[]}""")
                    }
                assertEquals(HttpStatusCode.OK, putResponse.status)
            }
        } finally {
            catalogServer.stop(0)
        }
    }

    @Test
    fun `board can GET user-scopes`() {
        val catalogServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        catalogServer.createContext("/user-scopes/user-1") { exchange ->
            val body = """{"siteIds":[],"assetIds":[]}""".toByteArray()
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
                            featureClient = featureClientWith("board"),
                            backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                            tokenValidator = TokenValidator.accepting(),
                        ),
                    )
                }
                val response =
                    client.get("/user-scopes/user-1") {
                        header(HttpHeaders.Authorization, "Bearer good")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }
        } finally {
            catalogServer.stop(0)
        }
    }

    @Test
    fun `board PUT user-scopes forbidden`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = featureClientWith("board"),
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }
        val response =
            client.put("/user-scopes/user-1") {
                header(HttpHeaders.Authorization, "Bearer good")
                contentType(ContentType.Application.Json)
                setBody("""{"siteIds":[],"assetIds":[]}""")
            }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `tickets cannot GET or PUT user-scopes`() = testApplication {
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

        val getResponse =
            client.get("/user-scopes/user-1") {
                header(HttpHeaders.Authorization, "Bearer good")
            }
        assertEquals(HttpStatusCode.Forbidden, getResponse.status)

        val putResponse =
            client.put("/user-scopes/user-1") {
                header(HttpHeaders.Authorization, "Bearer good")
                contentType(ContentType.Application.Json)
                setBody("""{"siteIds":[],"assetIds":[]}""")
            }
        assertEquals(HttpStatusCode.Forbidden, putResponse.status)
    }

    @Test
    fun `GET user-scopes covers forbidden without board feature`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = featureClientWith("equipment"),
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }
        val response =
            client.get("/user-scopes/user-1/covers/asset-1") {
                header(HttpHeaders.Authorization, "Bearer good")
            }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
