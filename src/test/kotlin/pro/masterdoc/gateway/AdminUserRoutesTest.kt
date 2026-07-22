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

        override suspend fun setRoles(userId: String, roles: List<String>): AdminUser {
            seenOrgIds += TenantContext.requireOrgId()
            return FakeZitadelAdminClient().setRoles(userId, roles)
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
                """{"userInfo":{"id":"a","roles":["admin"]},"features":${json.encodeToString(features.toList())}}"""
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
            setBody("""{"email":"a@b.com","givenName":"A","familyName":"B","roles":["admin"]}""")
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
                setBody("""{"email":"a@b.com","givenName":"A","familyName":"B","roles":["admin"]}""")
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
                    """{"email":"ivan@company.ru","givenName":"Ivan","familyName":"Petrov","roles":["technologist"]}""",
                )
            }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["id"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals("ivan@company.ru", body["email"]!!.jsonPrimitive.content)
        assertEquals("technologist", body["roles"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(true, body["inviteSent"]!!.jsonPrimitive.content.toBooleanStrict())
    }

    @Test
    fun `POST invites with unknown role returns 400`() = testApplication {
        application {
            module(GatewayConfig.testDefaults(), testDeps(featureClientWith("user_invite")))
        }
        val response =
            client.post("/admin/users/invites") {
                header(HttpHeaders.Authorization, "Bearer good-token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"email":"a@b.com","givenName":"A","familyName":"B","roles":["foo"]}""")
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
            """{"email":"dup@company.ru","givenName":"A","familyName":"B","roles":["admin"]}"""
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
            setBody("""{"email":"list@company.ru","givenName":"L","familyName":"U","roles":["admin"]}""")
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
    fun `PUT roles with empty roles returns 400`() = testApplication {
        val fake = FakeZitadelAdminClient()
        application {
            module(GatewayConfig.testDefaults(), testDeps(featureClientWith("user_invite"), fake))
        }
        val inviteResponse =
            client.post("/admin/users/invites") {
                header(HttpHeaders.Authorization, "Bearer good-token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"email":"roles@company.ru","givenName":"R","familyName":"U","roles":["admin"]}""")
            }
        val userId =
            Json.parseToJsonElement(inviteResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val response =
            client.put("/admin/users/$userId/roles") {
                header(HttpHeaders.Authorization, "Bearer good-token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"roles":[]}""")
            }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `PUT roles with valid body returns 200 with updated roles`() = testApplication {
        val fake = FakeZitadelAdminClient()
        application {
            module(GatewayConfig.testDefaults(), testDeps(featureClientWith("user_invite"), fake))
        }
        val inviteResponse =
            client.post("/admin/users/invites") {
                header(HttpHeaders.Authorization, "Bearer good-token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"email":"update@company.ru","givenName":"U","familyName":"P","roles":["admin"]}""")
            }
        val userId =
            Json.parseToJsonElement(inviteResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val response =
            client.put("/admin/users/$userId/roles") {
                header(HttpHeaders.Authorization, "Bearer good-token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"roles":["technologist","reporter"]}""")
            }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("technologist", body["roles"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("reporter", body["roles"]!!.jsonArray[1].jsonPrimitive.content)
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
                roles = listOf("admin"),
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
                    """{"email":"ivan@company.ru","givenName":"Ivan","familyName":"Petrov","roles":["technologist"]}""",
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
                    tokenValidator = TokenValidator.rejecting(),
                ),
            )
        }
        val response =
            client.post("/admin/users/invites") {
                header(HttpHeaders.Authorization, "Bearer bad")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"email":"a@b.com","givenName":"A","familyName":"B","roles":["admin"]}""")
            }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
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
                    """{"email":"invited@company.ru","givenName":"I","familyName":"N","roles":["technologist"]}""",
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
    fun `DELETE active user returns 409`() = testApplication {
        val fake = FakeZitadelAdminClient()
        application {
            module(GatewayConfig.testDefaults(), testDeps(featureClientWith("user_invite"), fake))
        }
        val inviteResponse =
            client.post("/admin/users/invites") {
                header(HttpHeaders.Authorization, "Bearer good-token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(
                    """{"email":"active@company.ru","givenName":"A","familyName":"C","roles":["technologist"]}""",
                )
            }
        val userId =
            Json.parseToJsonElement(inviteResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        fake.markActive(userId)

        val deleteResponse =
            client.delete("/admin/users/$userId") {
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
                roles = listOf("admin"),
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
