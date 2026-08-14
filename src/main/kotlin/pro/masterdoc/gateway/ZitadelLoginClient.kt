package pro.masterdoc.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.formUrlEncode
import io.ktor.http.isSuccess
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed interface ZitadelLoginResult {
    data class Code(
        val code: String,
        val codeVerifier: String,
        val redirectUri: String,
    ) : ZitadelLoginResult

    data object InvalidCredentials : ZitadelLoginResult
}

fun interface ZitadelLoginClient {
    suspend fun loginWithPassword(email: String, password: String, clientId: String): ZitadelLoginResult

    companion object {
        fun http(config: GatewayConfig): ZitadelLoginClient = HttpZitadelLoginClient(config)

        fun unconfigured(): ZitadelLoginClient =
            ZitadelLoginClient { _, _, _ -> error("Zitadel login client not configured") }
    }
}

internal class HttpZitadelLoginClient(
    private val config: GatewayConfig,
    private val client: HttpClient =
        HttpClient(CIO) {
            followRedirects = false
        },
    private val secureRandom: SecureRandom = SecureRandom(),
) : ZitadelLoginClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun loginWithPassword(
        email: String,
        password: String,
        clientId: String,
    ): ZitadelLoginResult {
        val verifier = randomBase64Url(32)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomBase64Url(24)
        val authRequestId = startAuthRequest(clientId, challenge, state)

        val session = createSession(email, password) ?: return ZitadelLoginResult.InvalidCredentials
        val code = finalizeAuthRequest(authRequestId, session)
        return ZitadelLoginResult.Code(code, verifier, config.nativeRedirectUri)
    }

    private suspend fun startAuthRequest(
        clientId: String,
        codeChallenge: String,
        state: String,
    ): String {
        val query =
            Parameters.build {
                append("client_id", clientId)
                append("redirect_uri", config.nativeRedirectUri)
                append("response_type", "code")
                append("scope", config.oidcScopes)
                append("state", state)
                append("code_challenge", codeChallenge)
                append("code_challenge_method", "S256")
            }.formUrlEncode()
        val response =
            upstream("authorize endpoint") {
                client.get("${config.zitadelIssuer.trimEnd('/')}/oauth/v2/authorize?$query")
            }
        val location = response.headers[HttpHeaders.Location]
        if (response.status.value !in 300..399 || location.isNullOrBlank()) {
            throw UpstreamUnavailableException("Zitadel authorize endpoint returned ${response.status.value}")
        }
        val parameters = URLBuilder(location).parameters
        return parameters["authRequest"]
            ?: parameters["id"]
            ?: throw UpstreamUnavailableException("Zitadel authorize redirect missing auth request id")
    }

    private suspend fun createSession(email: String, password: String): ZitadelSession? {
        val response =
            upstream("session endpoint") {
                client.post("${config.zitadelIssuer.trimEnd('/')}/v2/sessions") {
                    header(HttpHeaders.Authorization, "Bearer ${config.zitadelMgmtToken}")
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            CreateSessionRequest(
                                checks =
                                    SessionChecks(
                                        user = SessionUserCheck(loginName = email),
                                        password = SessionPasswordCheck(password = password),
                                    ),
                            ),
                        ),
                    )
                }
            }
        if (response.status == HttpStatusCode.BadRequest ||
            response.status == HttpStatusCode.Unauthorized ||
            response.status == HttpStatusCode.Forbidden
        ) {
            return null
        }
        if (!response.status.isSuccess()) {
            throw UpstreamUnavailableException("Zitadel session endpoint returned ${response.status.value}")
        }
        return decode("session endpoint", response.bodyAsText())
    }

    private suspend fun finalizeAuthRequest(
        authRequestId: String,
        session: ZitadelSession,
    ): String {
        val response =
            upstream("auth request endpoint") {
                client.post(
                    "${config.zitadelIssuer.trimEnd('/')}/v2/oidc/auth_requests/$authRequestId",
                ) {
                    header(HttpHeaders.Authorization, "Bearer ${config.zitadelMgmtToken}")
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            FinalizeAuthRequest(
                                session =
                                    FinalizeSession(
                                        sessionId = session.sessionId,
                                        sessionToken = session.sessionToken,
                                    ),
                            ),
                        ),
                    )
                }
            }
        if (!response.status.isSuccess()) {
            throw UpstreamUnavailableException("Zitadel auth request endpoint returned ${response.status.value}")
        }
        val callback = decode<FinalizeAuthResponse>("auth request endpoint", response.bodyAsText()).callbackUrl
        return URLBuilder(callback).parameters["code"]
            ?: throw UpstreamUnavailableException("Zitadel callback URL missing authorization code")
    }

    private suspend fun upstream(endpoint: String, request: suspend () -> io.ktor.client.statement.HttpResponse) =
        try {
            request()
        } catch (e: UpstreamUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw UpstreamUnavailableException("Zitadel $endpoint unavailable", e)
        }

    private inline fun <reified T> decode(endpoint: String, body: String): T =
        try {
            json.decodeFromString(body)
        } catch (e: Exception) {
            throw UpstreamUnavailableException("Invalid response from Zitadel $endpoint", e)
        }

    private fun randomBase64Url(size: Int): String =
        ByteArray(size).also(secureRandom::nextBytes).let(::base64Url)

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

@Serializable
private data class CreateSessionRequest(val checks: SessionChecks)

@Serializable
private data class SessionChecks(
    val user: SessionUserCheck,
    val password: SessionPasswordCheck,
)

@Serializable
private data class SessionUserCheck(val loginName: String)

@Serializable
private data class SessionPasswordCheck(val password: String)

@Serializable
private data class ZitadelSession(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("sessionToken") val sessionToken: String,
)

@Serializable
private data class FinalizeAuthRequest(val session: FinalizeSession)

@Serializable
private data class FinalizeSession(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("sessionToken") val sessionToken: String,
)

@Serializable
private data class FinalizeAuthResponse(
    @SerialName("callbackUrl") val callbackUrl: String,
)
