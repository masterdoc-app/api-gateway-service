package pro.masterdoc.gateway

import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AuthTokenRoutesTest {
    @Test
    fun `POST auth token proxies authorization_code exchange to Zitadel`() = testApplication {
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
                                """{"access_token":"at","refresh_token":"rt","token_type":"Bearer","expires_in":3600}"""
                                    .toByteArray(),
                            )
                        },
                ),
            )
        }
        val response =
            client.submitForm(
                url = "/auth/token",
                formParameters =
                    Parameters.build {
                        append("grant_type", "authorization_code")
                        append("code", "abc")
                        append("redirect_uri", "masterdoc://auth/callback")
                        append("client_id", "native-app")
                        append("code_verifier", "verifier")
                    },
            )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("at", body["access_token"]!!.jsonPrimitive.content)
        assertEquals("rt", body["refresh_token"]!!.jsonPrimitive.content)
        assertTrue(capturedForm!!.contains("grant_type=authorization_code"))
        assertTrue(capturedForm!!.contains("code=abc"))
        assertTrue(capturedForm!!.contains("code_verifier=verifier"))
    }

    @Test
    fun `POST auth token proxies refresh_token grant`() = testApplication {
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
                                """{"access_token":"new-at","token_type":"Bearer","expires_in":3600}""".toByteArray(),
                            )
                        },
                ),
            )
        }
        val response =
            client.submitForm(
                url = "/auth/token",
                formParameters =
                    Parameters.build {
                        append("grant_type", "refresh_token")
                        append("refresh_token", "old-rt")
                        append("client_id", "native-app")
                    },
            )
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(capturedForm!!.contains("grant_type=refresh_token"))
        assertTrue(capturedForm!!.contains("refresh_token=old-rt"))
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("new-at", body["access_token"]!!.jsonPrimitive.content)
    }

    @Test
    fun `POST auth token returns upstream error status and body`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = FeatureServiceClient { error("unused") },
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.rejecting(),
                    zitadelTokenClient =
                        ZitadelTokenClient {
                            UpstreamResult(
                                HttpStatusCode.BadRequest,
                                "application/json",
                                """{"error":"invalid_grant"}""".toByteArray(),
                            )
                        },
                ),
            )
        }
        val response =
            client.submitForm(
                url = "/auth/token",
                formParameters =
                    Parameters.build {
                        append("grant_type", "authorization_code")
                        append("code", "bad")
                        append("redirect_uri", "masterdoc://auth/callback")
                        append("client_id", "native-app")
                        append("code_verifier", "v")
                    },
            )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("invalid_grant"))
    }

    @Test
    fun `POST auth token returns 502 when Zitadel unavailable`() = testApplication {
        application {
            module(
                GatewayConfig.testDefaults(),
                GatewayDeps(
                    featureClient = FeatureServiceClient { error("unused") },
                    backendClient = BackendProxyClient { _, _, _, _ -> error("unused") },
                    tokenValidator = TokenValidator.rejecting(),
                    zitadelTokenClient =
                        ZitadelTokenClient {
                            throw UpstreamUnavailableException("zitadel down")
                        },
                ),
            )
        }
        val response =
            client.submitForm(
                url = "/auth/token",
                formParameters =
                    Parameters.build {
                        append("grant_type", "refresh_token")
                        append("refresh_token", "rt")
                        append("client_id", "native-app")
                    },
            ) {
                header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
            }
        assertEquals(HttpStatusCode.BadGateway, response.status)
    }
}
