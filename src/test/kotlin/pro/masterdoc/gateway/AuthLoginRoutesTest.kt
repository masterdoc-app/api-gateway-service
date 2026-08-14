package pro.masterdoc.gateway

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AuthLoginRoutesTest {
    @Test
    fun `POST auth login returns tokens on success`() = testApplication {
        var capturedForm: String? = null
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = FeatureServiceClient { error("unused") },
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.rejecting(),
                    zitadelTokenClient =
                        ZitadelTokenClient { formBody ->
                            capturedForm = formBody
                            UpstreamResult(
                                HttpStatusCode.OK,
                                "application/json",
                                """{"access_token":"at","refresh_token":"rt","token_type":"Bearer","expires_in":3600,"id_token":"id"}"""
                                    .toByteArray(),
                            )
                        },
                    zitadelLoginClient =
                        ZitadelLoginClient { _, _, _ ->
                            ZitadelLoginResult.Code(
                                code = "auth-code",
                                codeVerifier = "verifier",
                                redirectUri = "masterdoc://auth/callback",
                            )
                        },
                ),
            )
        }
        val response =
            client.post("/auth/login") {
                setBody(
                    TextContent(
                        """{"email":"a@b.c","password":"secret","client_id":"native"}""",
                        ContentType.Application.Json,
                    ),
                )
            }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("at", body["access_token"]!!.jsonPrimitive.content)
        assertEquals("rt", body["refresh_token"]!!.jsonPrimitive.content)
        assertTrue(capturedForm!!.contains("grant_type=authorization_code"))
        assertTrue(capturedForm!!.contains("code=auth-code"))
        assertTrue(capturedForm!!.contains("client_id=native"))
        assertTrue(capturedForm!!.contains("code_verifier=verifier"))
    }

    @Test
    fun `POST auth login blank fields returns 400`() = testApplication {
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
        val response =
            client.post("/auth/login") {
                setBody(
                    TextContent(
                        """{"email":"","password":"x","client_id":"native"}""",
                        ContentType.Application.Json,
                    ),
                )
            }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST auth login invalid credentials returns 401`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = FeatureServiceClient { error("unused") },
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.rejecting(),
                    zitadelLoginClient =
                        ZitadelLoginClient { _, _, _ ->
                            ZitadelLoginResult.InvalidCredentials
                        },
                ),
            )
        }
        val response =
            client.post("/auth/login") {
                setBody(
                    TextContent(
                        """{"email":"a@b.c","password":"bad","client_id":"native"}""",
                        ContentType.Application.Json,
                    ),
                )
            }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST auth login returns 502 when Zitadel unavailable`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = FeatureServiceClient { error("unused") },
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.rejecting(),
                    zitadelLoginClient =
                        ZitadelLoginClient { _, _, _ ->
                            throw UpstreamUnavailableException("zitadel down")
                        },
                ),
            )
        }
        val response =
            client.post("/auth/login") {
                setBody(
                    TextContent(
                        """{"email":"a@b.c","password":"secret","client_id":"native"}""",
                        ContentType.Application.Json,
                    ),
                )
            }
        assertEquals(HttpStatusCode.BadGateway, response.status)
    }

    @Test
    fun `POST auth login returns 502 when token exchange is rejected`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = FeatureServiceClient { error("unused") },
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.rejecting(),
                    zitadelLoginClient =
                        ZitadelLoginClient { _, _, _ ->
                            ZitadelLoginResult.Code(
                                code = "auth-code",
                                codeVerifier = "verifier",
                                redirectUri = "masterdoc://auth/callback",
                            )
                        },
                    zitadelTokenClient =
                        ZitadelTokenClient {
                            UpstreamResult(
                                HttpStatusCode.Unauthorized,
                                "application/json",
                                """{"error":"invalid_client"}""".toByteArray(),
                            )
                        },
                ),
            )
        }
        val response =
            client.post("/auth/login") {
                setBody(
                    TextContent(
                        """{"email":"a@b.c","password":"secret","client_id":"native"}""",
                        ContentType.Application.Json,
                    ),
                )
            }
        assertEquals(HttpStatusCode.BadGateway, response.status)
    }
}
