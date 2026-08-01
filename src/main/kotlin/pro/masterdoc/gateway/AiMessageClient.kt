package pro.masterdoc.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory

interface AiMessageClient {
    suspend fun listMessages(
        orgId: String,
        limit: Int,
        offset: Int = 0,
    ): UpstreamResult

    companion object {
        fun http(baseUrl: String, internalToken: String? = null): AiMessageClient =
            HttpAiMessageClient(baseUrl, internalToken)

        fun noop(): AiMessageClient =
            object : AiMessageClient {
                override suspend fun listMessages(orgId: String, limit: Int, offset: Int): UpstreamResult =
                    UpstreamResult(HttpStatusCode.OK, "application/json", """{"items":[]}""".toByteArray())
            }
    }
}

class HttpAiMessageClient(
    private val baseUrl: String,
    private val internalToken: String? = null,
    private val client: HttpClient = HttpClient(CIO),
) : AiMessageClient {
    override suspend fun listMessages(orgId: String, limit: Int, offset: Int): UpstreamResult {
        try {
            val response: HttpResponse =
                client.request(
                    "${baseUrl.trimEnd('/')}/messages?orgId=$orgId&limit=$limit&offset=${offset.coerceAtLeast(0)}",
                ) {
                    method = HttpMethod.Get
                    if (!internalToken.isNullOrBlank()) {
                        header("X-Internal-Token", internalToken)
                    }
                }
            return UpstreamResult(
                status = response.status,
                contentType = response.headers[HttpHeaders.ContentType],
                body = response.bodyAsBytes(),
            )
        } catch (e: Exception) {
            aiMessageLog.error("event=upstream_unavailable service=ai-message-service cause=${e.message}")
            throw UpstreamUnavailableException("ai-message-service unavailable", e)
        }
    }
}

private val aiMessageLog = LoggerFactory.getLogger("pro.masterdoc.gateway.AiMessageClient")
