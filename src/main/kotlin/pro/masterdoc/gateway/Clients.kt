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
import org.slf4j.LoggerFactory

private val clientsLog = LoggerFactory.getLogger("pro.masterdoc.gateway.Clients")

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
            clientsLog.error("event=upstream_unavailable service=feature-service cause=${e.message}")
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
            clientsLog.error("event=upstream_unavailable service=backend cause=${e.message}")
            throw UpstreamUnavailableException("backend unavailable", e)
        }
    }
}

fun interface TokenValidator {
    /** Valid token with subject; orgId may be null (allowed for /me, rejected on admin paths). */
    suspend fun validate(bearerToken: String): ValidatedToken?

    companion object {
        fun jwks(issuer: String, jwkSetUri: String): TokenValidator =
            JwksTokenValidator(issuer, jwkSetUri)

        fun accepting(
            subject: String = "test-sub",
            orgId: String = "test-org",
        ): TokenValidator = TokenValidator { ValidatedToken(subject, orgId) }

        fun acceptingWithoutOrg(subject: String = "test-sub"): TokenValidator =
            TokenValidator { ValidatedToken(subject, orgId = null) }

        fun rejecting(): TokenValidator = TokenValidator { null }
    }
}

/** Proxies OAuth token form body to Zitadel `POST /oauth/v2/token`. */
fun interface ZitadelTokenClient {
    suspend fun exchange(formBody: String): UpstreamResult

    companion object {
        fun http(issuer: String): ZitadelTokenClient = HttpZitadelTokenClient(issuer)
    }
}

class HttpZitadelTokenClient(
    private val issuer: String,
    private val client: HttpClient = HttpClient(CIO),
) : ZitadelTokenClient {
    override suspend fun exchange(formBody: String): UpstreamResult {
        try {
            val base = issuer.trimEnd('/')
            val response: HttpResponse =
                client.request("$base/oauth/v2/token") {
                    method = HttpMethod.Post
                    header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                    setBody(formBody)
                }
            return UpstreamResult(
                status = response.status,
                contentType = response.headers[HttpHeaders.ContentType],
                body = response.bodyAsBytes(),
            )
        } catch (e: Exception) {
            clientsLog.error("event=upstream_unavailable service=zitadel cause=${e.message}")
            throw UpstreamUnavailableException("zitadel token endpoint unavailable", e)
        }
    }
}
