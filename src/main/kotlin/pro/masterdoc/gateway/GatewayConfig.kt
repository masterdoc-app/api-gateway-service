package pro.masterdoc.gateway

data class GatewayConfig(
    val port: Int,
    val zitadelIssuer: String,
    val zitadelJwkSetUri: String,
    val zitadelOrgId: String,
    val zitadelProjectId: String,
    val zitadelMgmtToken: String,
    val featureServiceBaseUrl: String,
    val backendBaseUrl: String,
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
                zitadelOrgId = System.getenv("ZITADEL_ORG_ID") ?: "",
                zitadelProjectId = System.getenv("ZITADEL_PROJECT_ID") ?: "",
                zitadelMgmtToken = System.getenv("ZITADEL_MGMT_TOKEN") ?: "",
                featureServiceBaseUrl =
                    System.getenv("FEATURE_SERVICE_BASE_URL") ?: "http://127.0.0.1:8082",
                backendBaseUrl = System.getenv("BACKEND_BASE_URL") ?: "http://127.0.0.1:8081",
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
                zitadelOrgId = "",
                zitadelProjectId = "",
                zitadelMgmtToken = "",
                featureServiceBaseUrl = "http://feature.test",
                backendBaseUrl = "http://backend.test",
                corsOrigins = listOf("http://localhost:8080"),
            )
    }
}
