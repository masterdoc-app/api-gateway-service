package pro.masterdoc.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode

fun interface FeatureServiceClient {
    suspend fun getMe(authorizationHeader: String): UpstreamResult

    companion object {
        fun http(baseUrl: String): FeatureServiceClient = HttpFeatureServiceClient(baseUrl)
    }
}

fun interface BackendProxyClient {
    suspend fun proxy(
        method: HttpMethod,
        pathAndQuery: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): UpstreamResult

    companion object {
        fun http(baseUrl: String): BackendProxyClient = HttpBackendProxyClient(baseUrl)
    }
}

data class UpstreamResult(
    val status: HttpStatusCode,
    val contentType: String?,
    val body: ByteArray,
)

class UpstreamUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

class HttpFeatureServiceClient(
    private val baseUrl: String,
    private val client: HttpClient = HttpClient(CIO),
) : FeatureServiceClient {
    override suspend fun getMe(authorizationHeader: String): UpstreamResult {
        try {
            val response: HttpResponse =
                client.request("$baseUrl/me") {
                    method = HttpMethod.Get
                    header(HttpHeaders.Authorization, authorizationHeader)
                }
            return UpstreamResult(
                status = response.status,
                contentType = response.headers[HttpHeaders.ContentType],
                body = response.bodyAsBytes(),
            )
        } catch (e: Exception) {
            throw UpstreamUnavailableException("feature-service unavailable", e)
        }
    }
}

class HttpBackendProxyClient(
    private val baseUrl: String,
    private val client: HttpClient = HttpClient(CIO),
) : BackendProxyClient {
    override suspend fun proxy(
        method: HttpMethod,
        pathAndQuery: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): UpstreamResult {
        try {
            val response: HttpResponse =
                client.request("$baseUrl$pathAndQuery") {
                    this.method = method
                    headers.forEach { (k, v) -> header(k, v) }
                    if (body != null) setBody(body)
                }
            return UpstreamResult(
                status = response.status,
                contentType = response.headers[HttpHeaders.ContentType],
                body = response.bodyAsBytes(),
            )
        } catch (e: Exception) {
            throw UpstreamUnavailableException("backend unavailable", e)
        }
    }
}

fun interface TokenValidator {
    /** Returns subject if valid; null if invalid. */
    suspend fun validate(bearerToken: String): String?

    companion object {
        fun jwks(issuer: String, jwkSetUri: String): TokenValidator =
            JwksTokenValidator(issuer, jwkSetUri)

        fun accepting(): TokenValidator = TokenValidator { "test-sub" }

        fun rejecting(): TokenValidator = TokenValidator { null }
    }
}
