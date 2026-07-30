package pro.masterdoc.gateway

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.delete
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class EngineerLocationsProxyRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun featureClientWith(vararg features: String): FeatureServiceClient =
        FeatureServiceClient {
            UpstreamResult(
                HttpStatusCode.OK,
                "application/json",
                """{"userInfo":{"id":"a"},"features":${json.encodeToString(features.toList())}}"""
                    .toByteArray(),
            )
        }

    private fun testDeps(vararg features: String): GatewayDeps =
        GatewayDeps(
            featureClient = featureClientWith(*features),
            backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
            tokenValidator = TokenValidator.accepting(),
        )

    @Test
    fun `GET engineer-locations requires map feature`() = testApplication {
        application {
            module(GatewayConfig.testDefaults(), testDeps("engineer"))
        }

        val response =
            client.get("/engineer-locations") {
                header(HttpHeaders.Authorization, "Bearer good")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `PUT engineer-locations me requires engineer feature`() = testApplication {
        application {
            module(GatewayConfig.testDefaults(), testDeps("map"))
        }

        val response =
            client.put("/engineer-locations/me") {
                header(HttpHeaders.Authorization, "Bearer good")
                contentType(ContentType.Application.Json)
                setBody("""{"latitude":55.75,"longitude":37.62}""")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `DELETE engineer-locations me requires engineer feature`() = testApplication {
        application {
            module(GatewayConfig.testDefaults(), testDeps("map"))
        }

        val response =
            client.delete("/engineer-locations/me") {
                header(HttpHeaders.Authorization, "Bearer good")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET engineer-locations forwards to map service`() {
        val mapServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        mapServer.createContext("/engineer-locations") { exchange ->
            val body = """{"items":[{"userId":"engineer-1"}]}""".toByteArray()
            exchange.responseHeaders.add(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            exchange.sendResponseHeaders(HttpStatusCode.OK.value, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        mapServer.start()

        try {
            testApplication {
                application {
                    module(
                        GatewayConfig.testDefaults().copy(
                            mapServiceBaseUrl = "http://127.0.0.1:${mapServer.address.port}",
                        ),
                        testDeps("map"),
                    )
                }

                val response =
                    client.get("/engineer-locations") {
                        header(HttpHeaders.Authorization, "Bearer good")
                    }

                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("""{"items":[{"userId":"engineer-1"}]}""", response.bodyAsText())
            }
        } finally {
            mapServer.stop(0)
        }
    }
}
