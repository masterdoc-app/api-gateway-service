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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FeaturesRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun testDeps(
        tokenValidator: TokenValidator = TokenValidator.accepting(),
    ): GatewayDeps =
        GatewayDeps(
            featureClient = FeatureServiceClient {
                UpstreamResult(HttpStatusCode.OK, "application/json", "{}".toByteArray())
            },
            backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
            tokenValidator = tokenValidator,
        )

    @Test
    fun `GET features without Authorization returns 401`() = testApplication {
        application {
            module(GatewayConfig.testDefaults(), testDeps())
        }
        val response = client.get("/features")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET features returns catalog with russian titles`() = testApplication {
        application {
            module(GatewayConfig.testDefaults(), testDeps())
        }
        val response =
            client.get("/features") {
                header(HttpHeaders.Authorization, "Bearer good-token")
            }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val items = body["items"]!!.jsonArray
        assertEquals(6, items.size)
        assertEquals("admin", items[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("Админ", items[0].jsonObject["titleRu"]!!.jsonPrimitive.content)
        assertTrue(items.any { it.jsonObject["id"]!!.jsonPrimitive.content == "black_box" })
        assertTrue(items.any { it.jsonObject["id"]!!.jsonPrimitive.content == "board" })
    }
}
