package pro.masterdoc.gateway

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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

    private class RecordingBlackBox : BlackBoxClient {
        val events = mutableListOf<CreateAuditEventRequest>()

        override suspend fun postEvent(event: CreateAuditEventRequest) {
            synchronized(events) { events += event }
        }

        override suspend fun listEvents(
            orgId: String,
            userId: String?,
            limit: Int,
            offset: Int,
        ): UpstreamResult =
            UpstreamResult(
                HttpStatusCode.OK,
                "application/json",
                """{"items":[]}""".toByteArray(),
            )
    }

    @Test
    fun `GET admin audit requires black_box not admin`() =
        testApplication {
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
                client.get("/admin/audit") {
                    header(HttpHeaders.Authorization, "Bearer good")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `GET admin audit allows black_box and forwards offset`() =
        testApplication {
            var seenLimit = -1
            var seenOffset = -1
            var seenUserId: String? = "unset"
            application {
                module(
                    GatewayConfig.testDefaults(),
                    GatewayDeps(
                        featureClient = featureClientWith("black_box"),
                        backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                        blackBoxClient =
                            object : BlackBoxClient {
                                override suspend fun postEvent(event: CreateAuditEventRequest) = Unit

                                override suspend fun listEvents(
                                    orgId: String,
                                    userId: String?,
                                    limit: Int,
                                    offset: Int,
                                ): UpstreamResult {
                                    assertEquals("test-org", orgId)
                                    seenLimit = limit
                                    seenOffset = offset
                                    seenUserId = userId
                                    return UpstreamResult(
                                        HttpStatusCode.OK,
                                        "application/json",
                                        """{"items":[{"id":"1","orgId":"test-org","userId":"u9","at":"t","method":"POST","path":"/sites","status":201}]}"""
                                            .toByteArray(),
                                    )
                                }
                            },
                        tokenValidator = TokenValidator.accepting(),
                    ),
                )
            }
            val response =
                client.get("/admin/audit?limit=30&offset=30&userId=u9") {
                    header(HttpHeaders.Authorization, "Bearer good")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(true, response.bodyAsText().contains("\"/sites\""))
            assertEquals(30, seenLimit)
            assertEquals(30, seenOffset)
            assertEquals("u9", seenUserId)
        }

    @Test
    fun `GET admin audit skips audit_list append when offset greater than zero`() =
        testApplication {
            val box = RecordingBlackBox()
            application {
                module(
                    GatewayConfig.testDefaults(),
                    GatewayDeps(
                        featureClient = featureClientWith("black_box"),
                        backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                        blackBoxClient = box,
                        tokenValidator = TokenValidator.accepting(),
                    ),
                )
            }
            val page0 =
                client.get("/admin/audit?limit=30&offset=0") {
                    header(HttpHeaders.Authorization, "Bearer good")
                }
            assertEquals(HttpStatusCode.OK, page0.status)
            runBlocking { delay(400) }
            assertTrue(box.events.any { it.action == "audit.list" }, "offset=0 should record audit.list")

            box.events.clear()
            val page1 =
                client.get("/admin/audit?limit=30&offset=30") {
                    header(HttpHeaders.Authorization, "Bearer good")
                }
            assertEquals(HttpStatusCode.OK, page1.status)
            runBlocking { delay(400) }
            assertEquals(emptyList(), box.events.filter { it.action == "audit.list" })
        }
}
