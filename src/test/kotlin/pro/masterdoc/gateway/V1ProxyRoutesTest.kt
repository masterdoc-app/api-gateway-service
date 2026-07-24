package pro.masterdoc.gateway

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class V1ProxyRoutesTest {
    @Test
    fun `GET v1 assistants proxies backend body`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = FeatureServiceClient { error("unused") },
                    backendClient =
                        BackendProxyClient { method, pathAndQuery, _, _ ->
                            assertEquals(HttpMethod.Get, method)
                            assertEquals("/v1/assistants", pathAndQuery)
                            UpstreamResult(
                                HttpStatusCode.OK,
                                "application/json",
                                """[{"id":1}]""".toByteArray(),
                            )
                        },
                    tokenValidator = TokenValidator.rejecting(),
                ),
            )
        }
        val response = client.get("/v1/assistants")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""[{"id":1}]""", response.bodyAsText())
    }

    @Test
    fun `POST v1 forwards body strips hop-by-hop and keeps Authorization`() = testApplication {
        var capturedHeaders: Map<String, String>? = null
        var capturedBody: ByteArray? = null
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = FeatureServiceClient { error("unused") },
                    backendClient =
                        BackendProxyClient { method, pathAndQuery, headers, body ->
                            assertEquals(HttpMethod.Post, method)
                            assertEquals("/v1/items", pathAndQuery)
                            capturedHeaders = headers
                            capturedBody = body
                            UpstreamResult(
                                HttpStatusCode.Created,
                                "application/json",
                                """{"id":1}""".toByteArray(),
                            )
                        },
                    tokenValidator = TokenValidator.rejecting(),
                ),
            )
        }
        val requestBody = """{"name":"test"}"""
        val response =
            client.post("/v1/items") {
                header(HttpHeaders.Host, "evil.example")
                header(HttpHeaders.Connection, "keep-alive")
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(requestBody, capturedBody?.decodeToString())
        val forwarded = capturedHeaders!!
        assertFalse(forwarded.containsKey(HttpHeaders.Host))
        assertFalse(forwarded.containsKey(HttpHeaders.Connection))
        assertEquals("Bearer secret-token", forwarded[HttpHeaders.Authorization])
    }

    @Test
    fun `v1 returns 502 when backend unavailable`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = FeatureServiceClient { error("unused") },
                    backendClient =
                        BackendProxyClient { _, _, _, _ ->
                            throw UpstreamUnavailableException("down")
                        },
                    tokenValidator = TokenValidator.rejecting(),
                ),
            )
        }
        val response = client.get("/v1/assistants")
        assertEquals(HttpStatusCode.BadGateway, response.status)
    }
}
