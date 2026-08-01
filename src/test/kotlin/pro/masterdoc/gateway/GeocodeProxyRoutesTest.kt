package pro.masterdoc.gateway

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

class GeocodeProxyRoutesTest {
    private fun featureClientWith(vararg features: String): FeatureServiceClient =
        FeatureServiceClient {
            UpstreamResult(
                HttpStatusCode.OK,
                "application/json",
                """{"userInfo":{"id":"a"},"features":${features.toList().joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")}}"""
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
    fun `GET geocode suggest requires admin or equipment feature`() = testApplication {
        application {
            module(GatewayConfig.testDefaults(), testDeps("map"))
        }

        val response =
            client.get("/geocode/suggest?q=Москва") {
                header(HttpHeaders.Authorization, "Bearer good")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET geocode suggest forwards query to map service for admin`() {
        val receivedQuery = AtomicReference<String>()
        val mapServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        mapServer.createContext("/geocode/suggest") { exchange ->
            receivedQuery.set(exchange.requestURI.rawQuery)
            val body = """{"items":[]}""".toByteArray()
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
                        testDeps("admin"),
                    )
                }

                val response =
                    client.get("/geocode/suggest?q=moscow&limit=5") {
                        header(HttpHeaders.Authorization, "Bearer good")
                    }

                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("""{"items":[]}""", response.bodyAsText())
                assertEquals("q=moscow&limit=5", receivedQuery.get())
            }
        } finally {
            mapServer.stop(0)
        }
    }
}
