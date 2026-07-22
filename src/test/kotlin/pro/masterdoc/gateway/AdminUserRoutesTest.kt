package pro.masterdoc.gateway

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AdminUserRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun featureClientWith(vararg features: String): FeatureServiceClient =
        FeatureServiceClient {
            UpstreamResult(
                HttpStatusCode.OK,
                "application/json",
                """{"userInfo":{"id":"a"},"features":${json.encodeToString(features.toList())}}"""
                    .toByteArray(),
            )
        }

    private fun testDeps(
        featureClient: FeatureServiceClient,
        zitadelAdminClient: ZitadelAdminClient = FakeZitadelAdminClient(),
        tokenValidator: TokenValidator = TokenValidator.accepting(),
    ): GatewayDeps =
        GatewayDeps(
            featureClient = featureClient,
            backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
            tokenValidator = tokenValidator,
            zitadelAdminClient = zitadelAdminClient,
        )

    @Test
    fun `POST invites without Authorization returns 401`() = testApplication {
        application {
            module(GatewayConfig.testDefaults(), testDeps(featureClientWith("user_invite")))
        }
        val response = client.post("/admin/users/invites") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"email":"a@b.com","givenName":"A","familyName":"B","features":["user_invite"]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST invites with valid token but no user_invite returns 403`() = testApplication {
        application {
            module(GatewayConfig.testDefaults(), testDeps(featureClientWith("board")))
        }
        val response =
            client.post("/admin/users/invites") {
                header(HttpHeaders.Authorization, "Bearer good-token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"email":"a@b.com","givenName":"A","familyName":"B","features":["user_invite"]}""")
            }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `POST invites with user_invite returns 201 with user details`() = testApplication {
        application {
            module(GatewayConfig.testDefaults(), testDeps(featureClientWith("user_invite")))
        }
        val response =
            client.post("/admin/users/invites") {
                header(HttpHeaders.Authorization, "Bearer good-token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(
                    """{"email":"ivan@company.ru","givenName":"Ivan","familyName":"Petrov","features":["charts","equipment"]}""",
                )
            }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["id"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals("ivan@company.ru", body["email"]!!.jsonPrimitive.content)
        assertEquals("charts", body["features"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(true, body["inviteSent"]!!.jsonPrimitive.content.toBooleanStrict())
    }

    @Test
    fun `POST invites with unknown feature returns 400`() = testApplication {
        application {
            module(GatewayConfig.testDefaults(), testDeps(featureClientWith("user_invite")))
        }
        val response =
            client.post("/admin/users/invites") {
                header(HttpHeaders.Authorization, "Bearer good-token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"email":"a@b.com","givenName":"A","familyName":"B","features":["foo"]}""")
            }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST invites with duplicate email returns 409`() = testApplication {
        val fake = FakeZitadelAdminClient()
        application {
            module(GatewayConfig.testDefaults(), testDeps(featureClientWith("user_invite"), fake))
        }
        val body =
            """{"email":"dup@company.ru","givenName":"A","familyName":"B","features":["user_invite"]}"""
        client.post("/admin/users/invites") {
            header(HttpHeaders.Authorization, "Bearer good-token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }
        val response =
            client.post("/admin/users/invites") {
                header(HttpHeaders.Authorization, "Bearer good-token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(body)
            }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `GET admin users returns 200 with items and total`() = testApplication {
        val fake = FakeZitadelAdminClient()
        application {
            module(GatewayConfig.testDefaults(), testDeps(featureClientWith("user_invite"), fake))
        }
        client.post("/admin/users/invites") {
            header(HttpHeaders.Authorization, "Bearer good-token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"email":"list@company.ru","givenName":"L","familyName":"U","features":["user_invite"]}""")
        }
        val response =
            client.get("/admin/users") {
                header(HttpHeaders.Authorization, "Bearer good-token")
            }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, body["total"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, body["items"]!!.jsonArray.size)
    }

    @Test
    fun `PUT features with empty features returns 400`() = testApplication {
        val fake = FakeZitadelAdminClient()
        application {
            module(GatewayConfig.testDefaults(), testDeps(featureClientWith("user_invite"), fake))
        }
        val inviteResponse =
            client.post("/admin/users/invites") {
                header(HttpHeaders.Authorization, "Bearer good-token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"email":"feat@company.ru","givenName":"R","familyName":"U","features":["user_invite"]}""")
            }
        val userId =
            Json.parseToJsonElement(inviteResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val response =
            client.put("/admin/users/$userId/features") {
                header(HttpHeaders.Authorization, "Bearer good-token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"features":[]}""")
            }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `PUT features with valid body returns 200 with updated features`() = testApplication {
        val fake = FakeZitadelAdminClient()
        application {
            module(GatewayConfig.testDefaults(), testDeps(featureClientWith("user_invite"), fake))
        }
        val inviteResponse =
            client.post("/admin/users/invites") {
                header(HttpHeaders.Authorization, "Bearer good-token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"email":"update@company.ru","givenName":"U","familyName":"P","features":["user_invite"]}""")
            }
        val userId =
            Json.parseToJsonElement(inviteResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val response =
            client.put("/admin/users/$userId/features") {
                header(HttpHeaders.Authorization, "Bearer good-token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"features":["charts","equipment"]}""")
            }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("charts", body["features"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("equipment", body["features"]!!.jsonArray[1].jsonPrimitive.content)
    }

    @Test
    fun `POST resend-invite on active user returns 409`() = testApplication {
        val fake = FakeZitadelAdminClient()
        fake.seed(
            AdminUser(
                id = "active-user",
                email = "active@company.ru",
                givenName = "A",
                familyName = "U",
                features = listOf("user_invite"),
                state = "active",
            ),
        )
        fake.markActive("active-user")
        application {
            module(GatewayConfig.testDefaults(), testDeps(featureClientWith("user_invite"), fake))
        }
        val response =
            client.post("/admin/users/active-user/resend-invite") {
                header(HttpHeaders.Authorization, "Bearer good-token")
            }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `POST resend-invite on invited user returns 204`() = testApplication {
        val fake = FakeZitadelAdminClient()
        fake.seed(
            AdminUser(
                id = "invited-user",
                email = "invited@company.ru",
                givenName = "I",
                familyName = "U",
                features = listOf("user_invite"),
                state = "invited",
                inviteSent = true,
            ),
        )
        application {
            module(GatewayConfig.testDefaults(), testDeps(featureClientWith("user_invite"), fake))
        }
        val response =
            client.post("/admin/users/invited-user/resend-invite") {
                header(HttpHeaders.Authorization, "Bearer good-token")
            }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }
}
