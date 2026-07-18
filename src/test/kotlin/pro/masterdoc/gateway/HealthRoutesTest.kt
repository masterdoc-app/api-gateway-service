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

class HealthRoutesTest {
    @Test
    fun `GET health returns 200 and status ok`() = testApplication {
        application { module(GatewayConfig.testDefaults()) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ok", body["status"]?.jsonPrimitive?.content)
    }
}
