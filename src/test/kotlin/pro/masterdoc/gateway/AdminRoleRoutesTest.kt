package pro.masterdoc.gateway

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class AdminRoleRoutesTest {
    private fun deps(
        features: List<String>,
        rolesClient: FeatureRolesClient,
        tokenValidator: TokenValidator = TokenValidator.accepting(orgId = "org-1"),
    ) = GatewayDeps(
        featureClient =
            FeatureServiceClient {
                UpstreamResult(
                    HttpStatusCode.OK,
                    "application/json",
                    """{"features":${features.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")}}"""
                        .toByteArray(),
                )
            },
        backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
        featureRolesClient = rolesClient,
        tokenValidator = tokenValidator,
    )

    @Test
    fun `admin can list and update roles with forwarded auth and org`() = testApplication {
        val roleClient =
            object : FeatureRolesClient {
                override suspend fun getRoles(authorizationHeader: String, orgId: String): UpstreamResult {
                    assertEquals("Bearer good-token", authorizationHeader)
                    assertEquals("org-1", orgId)
                    return UpstreamResult(HttpStatusCode.OK, "application/json", """{"items":[]}""".toByteArray())
                }

                override suspend fun updateRole(
                    authorizationHeader: String,
                    orgId: String,
                    roleId: String,
                    body: ByteArray,
                    contentType: String?,
                ): UpstreamResult {
                    assertEquals("Bearer good-token", authorizationHeader)
                    assertEquals("org-1", orgId)
                    assertEquals("manager", roleId)
                    assertEquals("""{"features":["reports"]}""", body.decodeToString())
                    return UpstreamResult(HttpStatusCode.OK, "application/json", """{"id":"manager"}""".toByteArray())
                }
            }
        application {
            module(GatewayConfig.testDefaults(), deps(listOf("admin"), roleClient))
        }
        val list = client.get("/admin/roles") { header(HttpHeaders.Authorization, "Bearer good-token") }
        assertEquals(HttpStatusCode.OK, list.status)
        val update =
            client.put("/admin/roles/manager") {
                header(HttpHeaders.Authorization, "Bearer good-token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"features":["reports"]}""")
            }
        assertEquals(HttpStatusCode.OK, update.status)
        assertEquals("""{"id":"manager"}""", update.bodyAsText())
    }

    @Test
    fun `non admin cannot access roles`() = testApplication {
        var called = false
        val roleClient =
            object : FeatureRolesClient {
                override suspend fun getRoles(authorizationHeader: String, orgId: String): UpstreamResult {
                    called = true
                    error("should not call")
                }

                override suspend fun updateRole(
                    authorizationHeader: String,
                    orgId: String,
                    roleId: String,
                    body: ByteArray,
                    contentType: String?,
                ): UpstreamResult = error("should not call")
            }
        application {
            module(GatewayConfig.testDefaults(), deps(listOf("board"), roleClient))
        }
        val response = client.get("/admin/roles") { header(HttpHeaders.Authorization, "Bearer good-token") }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(false, called)
    }
}
