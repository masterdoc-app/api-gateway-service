package pro.masterdoc.gateway

data class GatewayConfig(
    val port: Int,
    val zitadelIssuer: String,
    val zitadelJwkSetUri: String,
    val featureServiceBaseUrl: String,
    val backendBaseUrl: String,
    val corsOrigins: List<String>,
) {
    companion object {
        fun fromEnv(): GatewayConfig =
            GatewayConfig(
                port = System.getenv("PORT")?.toIntOrNull() ?: 8083,
                zitadelIssuer = System.getenv("ZITADEL_ISSUER") ?: "https://auth.formaverse.ru",
                zitadelJwkSetUri =
                    System.getenv("ZITADEL_JWK_SET_URI")
                        ?: "https://auth.formaverse.ru/oauth/v2/keys",
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
                featureServiceBaseUrl = "http://feature.test",
                backendBaseUrl = "http://backend.test",
                corsOrigins = listOf("http://localhost:8080"),
            )
    }
}
