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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AttachmentProxyRoutesTest {
    private val json = Json

    private fun featureClientWith(vararg features: String): FeatureServiceClient =
        FeatureServiceClient {
            UpstreamResult(
                HttpStatusCode.OK,
                "application/json",
                """{"userInfo":{"id":"a"},"features":${json.encodeToString(features.toList())}}""".toByteArray(),
            )
        }

    @Test
    fun `GET attachments requires attachment feature`() = testApplication {
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
            client.get("/attachments/a-1") {
                header(HttpHeaders.Authorization, "Bearer good")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET attachments allowed with tickets feature`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults().copy(attachmentServiceBaseUrl = "http://127.0.0.1:1"),
                GatewayDeps(
                    featureClient = featureClientWith("tickets"),
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }

        val response =
            client.get("/attachments/a-1") {
                header(HttpHeaders.Authorization, "Bearer good")
            }

        assertTrue(response.status == HttpStatusCode.BadGateway || response.status == HttpStatusCode.OK)
        assertTrue(response.status != HttpStatusCode.Forbidden)
    }

    @Test
    fun `POST attachments allowed with engineer feature`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults().copy(attachmentServiceBaseUrl = "http://127.0.0.1:1"),
                GatewayDeps(
                    featureClient = featureClientWith("engineer"),
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.accepting(),
                ),
            )
        }

        val response =
            client.post("/attachments") {
                header(HttpHeaders.Authorization, "Bearer good")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"photo.jpg"}""")
            }

        assertTrue(response.status == HttpStatusCode.BadGateway || response.status == HttpStatusCode.Created)
        assertTrue(response.status != HttpStatusCode.Forbidden)
    }
}
