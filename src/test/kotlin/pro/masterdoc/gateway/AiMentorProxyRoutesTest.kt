package pro.masterdoc.gateway

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.header
import io.ktor.client.request.post
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

class AiMentorProxyRoutesTest {
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
    fun `POST ai mentor forbidden without engineer feature`() = testApplication {
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
            client.post("/ai/mentor") {
                header(HttpHeaders.Authorization, "Bearer good")
                contentType(ContentType.Application.Json)
                setBody("""{"workOrderId":"wo-1","message":"help"}""")
            }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `POST ai mentor proxies to technologist with engineer feature`() {
        var capturedPath: String? = null
        var capturedOrg: String? = null
        val technologistServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        technologistServer.createContext("/ai/mentor") { exchange ->
            capturedPath = exchange.requestURI.path
            capturedOrg = exchange.requestHeaders.getFirst("X-Org-Id")
            val body = """{"reply":"ok"}""".toByteArray()
            exchange.responseHeaders.add(HttpHeaders.ContentType, "application/json")
            exchange.sendResponseHeaders(HttpStatusCode.OK.value, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        technologistServer.start()

        try {
            testApplication {
                application {
                    module(
                        GatewayConfig.testDefaults().copy(
                            technologistServiceBaseUrl =
                                "http://127.0.0.1:${technologistServer.address.port}",
                        ),
                        GatewayDeps(
                            featureClient = featureClientWith("engineer"),
                            backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                            tokenValidator = TokenValidator.accepting(),
                        ),
                    )
                }

                val response =
                    client.post("/ai/mentor") {
                        header(HttpHeaders.Authorization, "Bearer good")
                        contentType(ContentType.Application.Json)
                        setBody("""{"workOrderId":"wo-1","message":"help"}""")
                    }

                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("""{"reply":"ok"}""", response.bodyAsText())
                assertEquals("/ai/mentor", capturedPath)
                assertEquals("test-org", capturedOrg)
            }
        } finally {
            technologistServer.stop(0)
        }
    }
}
