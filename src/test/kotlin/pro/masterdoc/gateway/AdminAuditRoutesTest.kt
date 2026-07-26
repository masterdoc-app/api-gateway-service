package pro.masterdoc.gateway

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AdminAuditRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun featureClientWith(vararg features: String): FeatureServiceClient =
        FeatureServiceClient {
            UpstreamResult(
                HttpStatusCode.OK,
                "application/json",
                """{"userInfo":{"id":"a","roles":["admin"]},"features":${json.encodeToString(features.toList())}}"""
                    .toByteArray(),
            )
        }

    @Test
    fun `GET admin audit requires admin`() = testApplication {
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
            client.get("/admin/audit") {
                header(HttpHeaders.Authorization, "Bearer good")
            }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET admin audit proxies black-box`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = featureClientWith("admin"),
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    blackBoxClient =
                        object : BlackBoxClient {
                            override suspend fun postEvent(event: CreateAuditEventRequest) = Unit

                            override suspend fun listEvents(
                                orgId: String,
                                userId: String?,
                                limit: Int,
                            ): UpstreamResult {
                                assertEquals("test-org", orgId)
                                return UpstreamResult(
                                    HttpStatusCode.OK,
                                    "application/json",
                                    """{"items":[{"id":"1","orgId":"test-org","userId":"u","at":"t","method":"POST","path":"/sites","status":201}]}"""
                                        .toByteArray(),
                                )
                            }
                        },
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }
        val response =
            client.get("/admin/audit") {
                header(HttpHeaders.Authorization, "Bearer good")
            }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(true, response.bodyAsText().contains("\"/sites\""))
    }
}
