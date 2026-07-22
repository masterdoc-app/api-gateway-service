package pro.masterdoc.gateway

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MeRoutesTest {
    @Test
    fun `GET me without Authorization returns 401`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = FeatureServiceClient { error("should not call") },
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.rejecting(),
                ),
            )
        }
        val response = client.get("/me")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET me with valid token proxies feature-service body`() = testApplication {
        val upstreamJson =
            """{"userInfo":{"id":"u1","givenName":"Ivan","familyName":"Petrov","email":"i@e.com"},"features":["board"]}"""
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient =
                        FeatureServiceClient { auth ->
                            assertEquals("Bearer good-token", auth)
                            UpstreamResult(HttpStatusCode.OK, "application/json", upstreamJson.toByteArray())
                        },
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }
        val response =
            client.get("/me") {
                header(HttpHeaders.Authorization, "Bearer good-token")
            }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("u1", body["userInfo"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("board", body["features"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `GET me with valid token without org claim proxies feature-service body`() = testApplication {
        val upstreamJson =
            """{"userInfo":{"id":"u1","givenName":"Ivan","familyName":"Petrov","email":"i@e.com","roles":["dispatcher"]},"features":["board"]}"""
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient =
                        FeatureServiceClient { auth ->
                            assertEquals("Bearer good-token", auth)
                            UpstreamResult(HttpStatusCode.OK, "application/json", upstreamJson.toByteArray())
                        },
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.acceptingWithoutOrg(),
                ),
            )
        }
        val response =
            client.get("/me") {
                header(HttpHeaders.Authorization, "Bearer good-token")
            }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET me returns 502 when feature-service unavailable`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient =
                        FeatureServiceClient {
                            throw UpstreamUnavailableException("down")
                        },
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }
        val response =
            client.get("/me") {
                header(HttpHeaders.Authorization, "Bearer good-token")
            }
        assertEquals(HttpStatusCode.BadGateway, response.status)
    }

    @Test
    fun `GET me with invalid token returns 401`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = FeatureServiceClient { error("should not call") },
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.rejecting(),
                ),
            )
        }
        val response =
            client.get("/me") {
                header(HttpHeaders.Authorization, "Bearer bad-token")
            }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
