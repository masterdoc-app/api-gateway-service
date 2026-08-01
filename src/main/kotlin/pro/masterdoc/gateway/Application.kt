package pro.masterdoc.gateway

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val applicationLog = LoggerFactory.getLogger("pro.masterdoc.gateway.Application")

fun main() {
    val config = GatewayConfig.fromEnv()
    applicationLog.info(
        "event=startup port=${config.port} " +
            "zitadelIssuerConfigured=${config.zitadelIssuer.isNotBlank()} " +
            "zitadelJwkSetUriConfigured=${config.zitadelJwkSetUri.isNotBlank()} " +
            "zitadelProjectIdConfigured=${config.zitadelProjectId.isNotBlank()} " +
            "zitadelMgmtTokenConfigured=${config.zitadelMgmtToken.isNotBlank()} " +
            "blackBoxInternalTokenConfigured=${config.blackBoxInternalToken.isNotBlank()} " +
            "aiMessageInternalTokenConfigured=${config.aiMessageInternalToken.isNotBlank()}",
    )
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(config)
    }.start(wait = true)
}

fun Application.module(
    config: GatewayConfig,
    deps: GatewayDeps = GatewayDeps.live(config),
) {
    environment.monitor.subscribe(ApplicationStopped) {
        applicationLog.info("event=shutdown")
    }
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
    installEquipmentRoutes(config, deps)
    installAdminUserRoutes(deps)
    installAdminAuditRoutes(deps)
    installAiMessageRoutes(deps)
    installClientEventsRoutes(deps)
}

data class GatewayDeps(
    val featureClient: FeatureServiceClient,
    val backendClient: BackendProxyClient,
    val blackBoxClient: BlackBoxClient = BlackBoxClient.noop(),
    val aiMessageClient: AiMessageClient = AiMessageClient.noop(),
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
                blackBoxClient =
                    BlackBoxClient.http(
                        config.blackBoxServiceBaseUrl,
                        config.blackBoxInternalToken.takeIf { it.isNotBlank() },
                    ),
                aiMessageClient =
                    AiMessageClient.http(
                        config.aiMessageServiceBaseUrl,
                        config.aiMessageInternalToken.takeIf { it.isNotBlank() },
                    ),
                tokenValidator = TokenValidator.jwks(config.zitadelIssuer, config.zitadelJwkSetUri),
                zitadelTokenClient = ZitadelTokenClient.http(config.zitadelIssuer),
                zitadelAdminClient = ZitadelAdminClient.http(config),
            )
    }
}

@Serializable
data class HealthResponse(val status: String)
