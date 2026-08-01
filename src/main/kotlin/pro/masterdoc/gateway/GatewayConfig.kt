package pro.masterdoc.gateway

data class GatewayConfig(
    val port: Int,
    val zitadelIssuer: String,
    val zitadelJwkSetUri: String,
    val zitadelProjectId: String,
    val zitadelMgmtToken: String,
    val featureServiceBaseUrl: String,
    val backendBaseUrl: String,
    val catalogServiceBaseUrl: String,
    val dashboardServiceBaseUrl: String,
    val maintenanceServiceBaseUrl: String,
    val documentServiceBaseUrl: String,
    val technologistServiceBaseUrl: String,
    val blackBoxServiceBaseUrl: String,
    val aiMessageServiceBaseUrl: String,
    val mapServiceBaseUrl: String,
    val blackBoxInternalToken: String,
    val aiMessageInternalToken: String,
    val corsOrigins: List<String>,
) {
    companion object {
        fun fromEnv(): GatewayConfig =
            GatewayConfig(
                port = System.getenv("PORT")?.toIntOrNull() ?: 8083,
                zitadelIssuer = System.getenv("ZITADEL_ISSUER") ?: "https://auth.fixaverse.ru",
                zitadelJwkSetUri =
                    System.getenv("ZITADEL_JWK_SET_URI")
                        ?: "https://auth.fixaverse.ru/oauth/v2/keys",
                zitadelProjectId = System.getenv("ZITADEL_PROJECT_ID") ?: "",
                zitadelMgmtToken = System.getenv("ZITADEL_MGMT_TOKEN") ?: "",
                featureServiceBaseUrl =
                    System.getenv("FEATURE_SERVICE_BASE_URL") ?: "http://127.0.0.1:8082",
                backendBaseUrl = System.getenv("BACKEND_BASE_URL") ?: "http://127.0.0.1:8081",
                catalogServiceBaseUrl = System.getenv("CATALOG_SERVICE_BASE_URL") ?: "http://127.0.0.1:8091",
                dashboardServiceBaseUrl = System.getenv("DASHBOARD_SERVICE_BASE_URL") ?: "http://127.0.0.1:8092",
                maintenanceServiceBaseUrl =
                    System.getenv("MAINTENANCE_SERVICE_BASE_URL") ?: "http://127.0.0.1:8098",
                documentServiceBaseUrl = System.getenv("DOCUMENT_SERVICE_BASE_URL") ?: "http://127.0.0.1:8093",
                technologistServiceBaseUrl =
                    System.getenv("TECHNOLOGIST_SERVICE_BASE_URL") ?: "http://127.0.0.1:8095",
                blackBoxServiceBaseUrl =
                    System.getenv("BLACK_BOX_SERVICE_BASE_URL") ?: "http://127.0.0.1:8096",
                aiMessageServiceBaseUrl =
                    System.getenv("AI_MESSAGE_SERVICE_BASE_URL") ?: "http://127.0.0.1:8097",
                mapServiceBaseUrl = System.getenv("MAP_SERVICE_BASE_URL") ?: "http://127.0.0.1:8100",
                blackBoxInternalToken = System.getenv("BLACK_BOX_INTERNAL_TOKEN") ?: "",
                aiMessageInternalToken = System.getenv("AI_MESSAGE_INTERNAL_TOKEN") ?: "",
                corsOrigins =
                    (System.getenv("CORS_ORIGINS") ?: "http://localhost:8080")
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
            )

        fun testDefaults(): GatewayConfig =
            GatewayConfig(
                port = 0,
                zitadelIssuer = "https://auth.test",
                zitadelJwkSetUri = "https://auth.test/keys",
                zitadelProjectId = "",
                zitadelMgmtToken = "",
                featureServiceBaseUrl = "http://feature.test",
                backendBaseUrl = "http://backend.test",
                catalogServiceBaseUrl = "http://catalog.test",
                dashboardServiceBaseUrl = "http://dashboard.test",
                maintenanceServiceBaseUrl = "http://maintenance.test",
                documentServiceBaseUrl = "http://document.test",
                technologistServiceBaseUrl = "http://technologist.test",
                blackBoxServiceBaseUrl = "http://blackbox.test",
                aiMessageServiceBaseUrl = "http://ai-message.test",
                mapServiceBaseUrl = "http://map.test",
                blackBoxInternalToken = "",
                aiMessageInternalToken = "",
                corsOrigins = listOf("http://localhost:8080"),
            )
    }
}
