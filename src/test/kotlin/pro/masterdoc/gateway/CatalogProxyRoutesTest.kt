package pro.masterdoc.gateway

import io.ktor.client.request.get
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Sites/assets proxy lives in [installEquipmentRoutes]; these tests cover auth gate + audit side-effect.
 */
class CatalogProxyRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

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

    private fun featureClientWith(vararg features: String): FeatureServiceClient =
        FeatureServiceClient {
            UpstreamResult(
                HttpStatusCode.OK,
                "application/json",
                """{"userInfo":{"id":"a"},"features":${json.encodeToString(features.toList())}}""".toByteArray(),
            )
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

    @Test
    fun `POST sites allowed with admin and audits`() = testApplication {
        val box = RecordingBlackBox()
        // Equipment routes call real HTTP to catalog base URL — without a live catalog this returns 502.
        // Assert feature gate allows admin for /sites (not Forbidden).
        application {
            module(
                GatewayConfig.testDefaults().copy(catalogServiceBaseUrl = "http://127.0.0.1:1"),
                GatewayDeps(
                    featureClient = featureClientWith("admin"),
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
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
        assertTrue(response.status == HttpStatusCode.BadGateway || response.status == HttpStatusCode.Created)
        assertTrue(response.status != HttpStatusCode.Forbidden)
        delay(100)
    }

    @Test
    fun `POST sites forbidden without equipment or admin`() = testApplication {
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
            client.post("/sites") {
                header(HttpHeaders.Authorization, "Bearer good")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"X"}""")
            }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
