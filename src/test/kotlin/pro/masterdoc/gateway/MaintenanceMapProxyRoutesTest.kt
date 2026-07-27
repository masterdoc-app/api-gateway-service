package pro.masterdoc.gateway

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MaintenanceMapProxyRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `GET maintenance-maps proxies maintenance service`() {
        val maintenanceServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        maintenanceServer.createContext("/maintenance-maps") { exchange ->
            val body = """{"source":"maintenance"}""".toByteArray()
            exchange.responseHeaders.add(HttpHeaders.ContentType, "application/json")
            exchange.sendResponseHeaders(HttpStatusCode.OK.value, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        maintenanceServer.start()

        try {
            testApplication {
                application {
                    module(
                        GatewayConfig.testDefaults().copy(
                            dashboardServiceBaseUrl = "http://127.0.0.1:1",
                            maintenanceServiceBaseUrl = "http://127.0.0.1:${maintenanceServer.address.port}",
                        ),
                        GatewayDeps(
                            featureClient =
                                FeatureServiceClient {
                                    UpstreamResult(
                                        HttpStatusCode.OK,
                                        "application/json",
                                        """{"userInfo":{"id":"a"},"features":${json.encodeToString(listOf("equipment"))}}"""
                                            .toByteArray(),
                                    )
                                },
                            backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                            tokenValidator = TokenValidator.accepting(),
                        ),
                    )
                }

                val response =
                    client.get("/maintenance-maps") {
                        header(HttpHeaders.Authorization, "Bearer good")
                    }

                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("""{"source":"maintenance"}""", response.bodyAsText())
            }
        } finally {
            maintenanceServer.stop(0)
        }
    }
}
