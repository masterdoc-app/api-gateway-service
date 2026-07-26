package pro.masterdoc.gateway

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay

class ClientEventsRoutesTest {
    private class RecordingBlackBox : BlackBoxClient {
        val events = mutableListOf<CreateAuditEventRequest>()

        override suspend fun postEvent(event: CreateAuditEventRequest) {
            events += event
        }

        override suspend fun listEvents(
            orgId: String,
            userId: String?,
            limit: Int,
            offset: Int,
        ): UpstreamResult = UpstreamResult(HttpStatusCode.OK, "application/json", """{"items":[]}""".toByteArray())
    }

    @Test
    fun `POST client-events without Authorization returns 401`() = testApplication {
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
        val response =
            client.post("/client-events") {
                contentType(ContentType.Application.Json)
                setBody("""{"action":"ui.shell.open","path":"MainShell"}""")
            }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST client-events without org claim returns 401`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = FeatureServiceClient { error("unused") },
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.acceptingWithoutOrg(),
                ),
            )
        }
        val response =
            client.post("/client-events") {
                header(HttpHeaders.Authorization, "Bearer good")
                contentType(ContentType.Application.Json)
                setBody("""{"action":"ui.shell.open","path":"MainShell"}""")
            }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST client-events records audit with org and subject`() = testApplication {
        val box = RecordingBlackBox()
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = FeatureServiceClient { error("unused") },
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    blackBoxClient = box,
                    tokenValidator = TokenValidator.accepting(subject = "user-1", orgId = "org-1"),
                ),
            )
        }
        val response =
            client.post("/client-events") {
                header(HttpHeaders.Authorization, "Bearer good")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"action":"ui.shell.nav.select","path":"MainShell","props":{"destination":"users"}}""",
                )
            }
        assertEquals(HttpStatusCode.Accepted, response.status)
        delay(200)
        assertEquals(1, box.events.size)
        val event = box.events.single()
        assertEquals("org-1", event.orgId)
        assertEquals("user-1", event.userId)
        assertEquals("UI", event.method)
        assertEquals("MainShell", event.path)
        assertEquals(200, event.status)
        assertEquals("ui.shell.nav.select", event.action)
        assertTrue(event.requestSummary!!.contains("destination"))
    }
}
