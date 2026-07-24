package pro.masterdoc.gateway

import io.ktor.client.request.delete
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

    private class RecordingOrgAdminClient : ZitadelAdminClient {
        val seenOrgIds = mutableListOf<String>()

        override suspend fun inviteUser(request: InviteUserRequest): AdminUser {
            seenOrgIds += TenantContext.requireOrgId()
            return FakeZitadelAdminClient().inviteUser(request)
        }

        override suspend fun listUsers(limit: Int, offset: Int): AdminUserList {
            seenOrgIds += TenantContext.requireOrgId()
            return AdminUserList(emptyList(), 0)
        }

        override suspend fun setFeatures(userId: String, features: List<String>): AdminUser {
            seenOrgIds += TenantContext.requireOrgId()
            return FakeZitadelAdminClient().setFeatures(userId, features)
        }

        override suspend fun resendInvite(userId: String) {
            seenOrgIds += TenantContext.requireOrgId()
        }

        override suspend fun deleteUser(userId: String) {
            seenOrgIds += TenantContext.requireOrgId()
        }
    }

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
    fun `POST invites uses org id from validated token not env`() = testApplication {
        val recording = RecordingOrgAdminClient()
        application {
            module(
                GatewayConfig.testDefaults(),
                testDeps(
                    featureClientWith("user_invite"),
                    zitadelAdminClient = recording,
                    tokenValidator = TokenValidator.accepting(orgId = "org-from-jwt"),
                ),
            )
        }
        val response =
            client.post("/admin/users/invites") {
                header(HttpHeaders.Authorization, "Bearer good-token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(
                    """{"email":"ivan@company.ru","givenName":"Ivan","familyName":"Petrov","features":["charts"]}""",
                )
            }
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(listOf("org-from-jwt"), recording.seenOrgIds)
    }

    @Test
    fun `POST invites with validator missing org returns 401`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                testDeps(
                    featureClientWith("user_invite"),
                    tokenValidator = TokenValidator.acceptingWithoutOrg(),
                ),
            )
        }
        val response =
            client.post("/admin/users/invites") {
                header(HttpHeaders.Authorization, "Bearer good-token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"email":"a@b.com","givenName":"A","familyName":"B","features":["user_invite"]}""")
            }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `admin routes scope org A vs org B without bleed`() = testApplication {
        val recording = RecordingOrgAdminClient()
        application {
            module(
                GatewayConfig.testDefaults(),
                testDeps(
                    featureClientWith("user_invite"),
                    zitadelAdminClient = recording,
                    tokenValidator =
                        TokenValidator { token ->
                            when (token) {
                                "token-org-a" -> ValidatedToken("sub-a", "org-a")
                                "token-org-b" -> ValidatedToken("sub-b", "org-b")
                                else -> null
                            }
                        },
                ),
            )
        }
        val inviteBody =
            """{"email":"ivan@company.ru","givenName":"Ivan","familyName":"Petrov","features":["charts"]}"""
        client.post("/admin/users/invites") {
            header(HttpHeaders.Authorization, "Bearer token-org-a")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(inviteBody)
        }
        client.get("/admin/users") {
            header(HttpHeaders.Authorization, "Bearer token-org-b")
        }
        assertEquals(listOf("org-a", "org-b"), recording.seenOrgIds)
    }

    @Test
    fun `DELETE invited user returns 204 and removes from list`() = testApplication {
        val fake = FakeZitadelAdminClient()
        application {
            module(GatewayConfig.testDefaults(), testDeps(featureClientWith("user_invite"), fake))
        }
        val inviteResponse =
            client.post("/admin/users/invites") {
                header(HttpHeaders.Authorization, "Bearer good-token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(
                    """{"email":"invited@company.ru","givenName":"I","familyName":"N","features":["charts"]}""",
                )
            }
        val userId =
            Json.parseToJsonElement(inviteResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val deleteResponse =
            client.delete("/admin/users/$userId") {
                header(HttpHeaders.Authorization, "Bearer good-token")
            }
        assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

        val listResponse =
            client.get("/admin/users") {
                header(HttpHeaders.Authorization, "Bearer good-token")
            }
        val total =
            Json.parseToJsonElement(listResponse.bodyAsText()).jsonObject["total"]!!.jsonPrimitive.content.toInt()
        assertEquals(0, total)
    }

    @Test
    fun `DELETE active user returns 204 and removes from list`() = testApplication {
        val fake = FakeZitadelAdminClient()
        application {
            module(GatewayConfig.testDefaults(), testDeps(featureClientWith("user_invite"), fake))
        }
        val inviteResponse =
            client.post("/admin/users/invites") {
                header(HttpHeaders.Authorization, "Bearer good-token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(
                    """{"email":"active@company.ru","givenName":"A","familyName":"C","features":["charts"]}""",
                )
            }
        val userId =
            Json.parseToJsonElement(inviteResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        fake.markActive(userId)

        val deleteResponse =
            client.delete("/admin/users/$userId") {
                header(HttpHeaders.Authorization, "Bearer good-token")
            }
        assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

        val listResponse =
            client.get("/admin/users") {
                header(HttpHeaders.Authorization, "Bearer good-token")
            }
        val total =
            Json.parseToJsonElement(listResponse.bodyAsText()).jsonObject["total"]!!.jsonPrimitive.content.toInt()
        assertEquals(0, total)
    }

    @Test
    fun `DELETE self returns 409`() = testApplication {
        val fake = FakeZitadelAdminClient()
        application {
            module(
                GatewayConfig.testDefaults(),
                testDeps(
                    featureClientWith("user_invite"),
                    fake,
                    tokenValidator = TokenValidator.accepting(subject = "self-user", orgId = "test-org"),
                ),
            )
        }
        fake.seed(
            AdminUser(
                id = "self-user",
                email = "self@company.ru",
                givenName = "Self",
                familyName = "User",
                features = listOf("user_invite"),
                state = "active",
            ),
        )
        val deleteResponse =
            client.delete("/admin/users/self-user") {
                header(HttpHeaders.Authorization, "Bearer good-token")
            }
        assertEquals(HttpStatusCode.Conflict, deleteResponse.status)
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
