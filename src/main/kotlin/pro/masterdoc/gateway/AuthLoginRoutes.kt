package pro.masterdoc.gateway

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.formUrlEncode
import io.ktor.http.isSuccess
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthLoginRequest(
    val email: String,
    val password: String,
    @SerialName("client_id") val clientId: String,
)

fun Application.installAuthLoginRoutes(deps: GatewayDeps) {
    routing {
        post("/auth/login") {
            val request =
                try {
                    call.receive<AuthLoginRequest>()
                } catch (_: Exception) {
                    call.respondText("Invalid request", status = HttpStatusCode.BadRequest)
                    return@post
                }
            if (request.email.isBlank() || request.password.isBlank() || request.clientId.isBlank()) {
                call.respondText("Invalid request", status = HttpStatusCode.BadRequest)
                return@post
            }

            try {
                when (
                    val login =
                        deps.zitadelLoginClient.loginWithPassword(
                            request.email,
                            request.password,
                            request.clientId,
                        )
                ) {
                    ZitadelLoginResult.InvalidCredentials ->
                        call.respondText(
                            "Invalid email or password",
                            status = HttpStatusCode.Unauthorized,
                        )

                    is ZitadelLoginResult.Code -> {
                        val formBody =
                            Parameters.build {
                                append("grant_type", "authorization_code")
                                append("code", login.code)
                                append("redirect_uri", login.redirectUri)
                                append("client_id", request.clientId)
                                append("code_verifier", login.codeVerifier)
                            }.formUrlEncode()
                        val upstream = deps.zitadelTokenClient.exchange(formBody)
                        if (!upstream.status.isSuccess()) {
                            throw UpstreamUnavailableException(
                                "Zitadel token endpoint returned ${upstream.status.value}",
                            )
                        }
                        val contentType =
                            upstream.contentType?.let(ContentType::parse)
                                ?: ContentType.Application.Json
                        call.respondBytes(upstream.body, contentType, upstream.status)
                    }
                }
            } catch (_: UpstreamUnavailableException) {
                call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
            }
        }
    }
}
