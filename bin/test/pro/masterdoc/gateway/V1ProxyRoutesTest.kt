package pro.masterdoc.gateway

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

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
