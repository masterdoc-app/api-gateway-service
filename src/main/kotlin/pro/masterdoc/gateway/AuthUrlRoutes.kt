package pro.masterdoc.gateway

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class AuthUrlResponse(val authUrl: String)

fun Application.installAuthUrlRoutes(config: GatewayConfig) {
    routing {
        get("/auth/url") {
            val issuer = config.zitadelIssuer.trimEnd('/')
            call.respond(AuthUrlResponse(authUrl = "$issuer/oauth/v2/authorize"))
        }
    }
}
