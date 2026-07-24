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
import kotlin.test.assertTrue
import kotlinx.coroutines.delay

class CatalogProxyRoutesTest {
    private class RecordingBlackBox : BlackBoxClient {
        val events = mutableListOf<CreateAuditEventRequest>()

        override suspend fun postEvent(event: CreateAuditEventRequest) {
            events += event
        }

        override suspend fun listEvents(
            orgId: String,
            userId: String?,
            limit: Int,
        ): UpstreamResult = UpstreamResult(HttpStatusCode.OK, "application/json", """{"items":[]}""".toByteArray())
    }

    @Test
    fun `POST sites proxies and audits on 2xx`() = testApplication {
        val box = RecordingBlackBox()
        var seenOrg: String? = null
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = FeatureServiceClient { error("unused") },
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    catalogClient =
                        BackendProxyClient { method, pathAndQuery, headers, body ->
                            assertEquals(HttpMethod.Post, method)
                            assertEquals("/sites", pathAndQuery)
                            seenOrg = headers["X-Org-Id"]
                            assertEquals("test-org", seenOrg)
                            assertTrue(headers[HttpHeaders.Authorization]!!.startsWith("Bearer "))
                            assertEquals("""{"name":"Цех 1"}""", body?.decodeToString())
                            UpstreamResult(
                                HttpStatusCode.Created,
                                "application/json",
                                """{"id":"цех-1","name":"Цех 1"}""".toByteArray(),
                            )
                        },
                    blackBoxClient = box,
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }
        val response =
            client.post("/sites") {
                header(HttpHeaders.Authorization, "Bearer good")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Цех 1"}""")
            }
        assertEquals(HttpStatusCode.Created, response.status)
        delay(200)
        assertEquals(1, box.events.size)
        assertEquals("site.create", box.events[0].action)
        assertEquals(201, box.events[0].status)
    }

    @Test
    fun `catalog 4xx does not audit`() = testApplication {
        val box = RecordingBlackBox()
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = FeatureServiceClient { error("unused") },
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    catalogClient =
                        BackendProxyClient { _, _, _, _ ->
                            UpstreamResult(HttpStatusCode.BadRequest, "text/plain", "Unknown siteId".toByteArray())
                        },
                    blackBoxClient = box,
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }
        val response =
            client.post("/assets") {
                header(HttpHeaders.Authorization, "Bearer good")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"X","siteId":"missing"}""")
            }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        delay(200)
        assertEquals(0, box.events.size)
    }

    @Test
    fun `GET assets without auth returns 401`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = FeatureServiceClient { error("unused") },
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.rejecting(),
                ),
            )
        }
        val response = client.get("/assets")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
