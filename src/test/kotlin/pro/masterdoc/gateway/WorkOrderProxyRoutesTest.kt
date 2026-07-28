package pro.masterdoc.gateway

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WorkOrderProxyRoutesTest {
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
    fun `GET work-orders board without auth returns 401`() = testApplication {
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
        val response = client.get("/work-orders/board")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET work-orders board allowed with engineer feature`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults().copy(dashboardServiceBaseUrl = "http://127.0.0.1:1"),
                GatewayDeps(
                    featureClient = featureClientWith("engineer"),
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }
        val response =
            client.get("/work-orders/board") {
                header(HttpHeaders.Authorization, "Bearer good")
            }
        assertTrue(response.status == HttpStatusCode.BadGateway || response.status == HttpStatusCode.OK)
        assertTrue(response.status != HttpStatusCode.Forbidden)
    }

    @Test
    fun `PATCH work-orders forbidden without board or engineer feature`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = featureClientWith("charts"),
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }
        val response =
            client.patch("/work-orders/wo-1") {
                header(HttpHeaders.Authorization, "Bearer good")
                contentType(ContentType.Application.Json)
                setBody("""{"assigneeId":"user-1"}""")
            }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `PATCH work-orders status allowed with engineer feature`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults().copy(dashboardServiceBaseUrl = "http://127.0.0.1:1"),
                GatewayDeps(
                    featureClient = featureClientWith("engineer"),
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }
        val response =
            client.patch("/work-orders/wo-1") {
                header(HttpHeaders.Authorization, "Bearer good")
                contentType(ContentType.Application.Json)
                setBody("""{"status":"in_progress"}""")
            }
        assertTrue(response.status == HttpStatusCode.BadGateway || response.status == HttpStatusCode.OK)
        assertTrue(response.status != HttpStatusCode.Forbidden)
    }

    @Test
    fun `GET work-orders board forbidden without board or engineer`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = featureClientWith("charts"),
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }
        val response =
            client.get("/work-orders/board") {
                header(HttpHeaders.Authorization, "Bearer good")
            }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET work-orders board forbidden with stale copilot-only grant`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = featureClientWith("copilot"),
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }
        val response =
            client.get("/work-orders/board") {
                header(HttpHeaders.Authorization, "Bearer good")
            }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET work-orders board allowed with board feature`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults().copy(dashboardServiceBaseUrl = "http://127.0.0.1:1"),
                GatewayDeps(
                    featureClient = featureClientWith("board"),
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }
        val response =
            client.get("/work-orders/board") {
                header(HttpHeaders.Authorization, "Bearer good")
            }
        assertTrue(response.status == HttpStatusCode.BadGateway || response.status == HttpStatusCode.OK)
        assertTrue(response.status != HttpStatusCode.Forbidden)
    }

    @Test
    fun `POST work-orders allowed with board feature`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults().copy(dashboardServiceBaseUrl = "http://127.0.0.1:1"),
                GatewayDeps(
                    featureClient = featureClientWith("board"),
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }
        val response =
            client.post("/work-orders") {
                header(HttpHeaders.Authorization, "Bearer good")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"type":"emergency","title":"X","assetId":"a1","siteId":"s1","dueAt":"2026-07-22"}""",
                )
            }
        assertTrue(response.status == HttpStatusCode.BadGateway || response.status == HttpStatusCode.Created)
        assertTrue(response.status != HttpStatusCode.Forbidden)
        assertTrue(response.status != HttpStatusCode.Unauthorized)
    }
}
