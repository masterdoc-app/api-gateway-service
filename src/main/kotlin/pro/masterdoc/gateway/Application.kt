package pro.masterdoc.gateway

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun main() {
    val config = GatewayConfig.fromEnv()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(config)
    }.start(wait = true)
}

fun Application.module(
    config: GatewayConfig,
    deps: GatewayDeps = GatewayDeps.live(config),
) {
    installObservability(config)
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            },
        )
    }
    routing {
        get("/health") {
            call.respond(HealthResponse(status = "ok"))
        }
    }
    installAuthUrlRoutes(config)
    installAuthTokenRoutes(deps)
    installMeRoutes(deps)
    installFeaturesRoutes(deps)
    installV1ProxyRoutes(deps)
    installAdminUserRoutes(deps)
}

data class GatewayDeps(
    val featureClient: FeatureServiceClient,
    val backendClient: BackendProxyClient,
    val tokenValidator: TokenValidator,
    val zitadelTokenClient: ZitadelTokenClient =
        ZitadelTokenClient { throw UpstreamUnavailableException("zitadel token client not configured") },
    val zitadelAdminClient: ZitadelAdminClient = ZitadelAdminClient.unconfigured(),
) {
    companion object {
        fun live(config: GatewayConfig): GatewayDeps =
            GatewayDeps(
                featureClient = FeatureServiceClient.http(config.featureServiceBaseUrl),
                backendClient = BackendProxyClient.http(config.backendBaseUrl),
                tokenValidator = TokenValidator.jwks(config.zitadelIssuer, config.zitadelJwkSetUri),
                zitadelTokenClient = ZitadelTokenClient.http(config.zitadelIssuer),
                zitadelAdminClient = ZitadelAdminClient.http(config),
            )
    }
}

@Serializable
data class HealthResponse(val status: String)
