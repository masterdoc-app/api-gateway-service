package pro.masterdoc.gateway

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AuthUrlRoutesTest {
    @Test
    fun `GET auth url returns 200 and authorize url without Authorization`() = testApplication {
        application { module(GatewayConfig.testDefaults()) }
        val response = client.get("/auth/url")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(
            "https://auth.test/oauth/v2/authorize",
            body["authUrl"]?.jsonPrimitive?.content,
        )
    }
}
