package pro.masterdoc.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun interface BlackBoxClient {
    suspend fun postEvent(event: CreateAuditEventRequest)

    suspend fun listEvents(
        orgId: String,
        userId: String?,
        limit: Int,
    ): UpstreamResult

    companion object {
        fun http(baseUrl: String, internalToken: String? = null): BlackBoxClient =
            HttpBlackBoxClient(baseUrl, internalToken)

        fun noop(): BlackBoxClient =
            object : BlackBoxClient {
                override suspend fun postEvent(event: CreateAuditEventRequest) = Unit

                override suspend fun listEvents(
                    orgId: String,
                    userId: String?,
                    limit: Int,
                ): UpstreamResult =
                    UpstreamResult(HttpStatusCode.OK, "application/json", """{"items":[]}""".toByteArray())
            }
    }
}

@Serializable
data class CreateAuditEventRequest(
    val orgId: String,
    val userId: String,
    val method: String,
    val path: String,
    val status: Int,
    val action: String? = null,
    val requestSummary: String? = null,
    val responseSummary: String? = null,
)

class HttpBlackBoxClient(
    private val baseUrl: String,
    private val internalToken: String? = null,
    private val client: HttpClient = HttpClient(CIO),
) : BlackBoxClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    override suspend fun postEvent(event: CreateAuditEventRequest) {
        try {
            client.request("${baseUrl.trimEnd('/')}/events") {
                method = HttpMethod.Post
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                if (!internalToken.isNullOrBlank()) {
                    header("X-Internal-Token", internalToken)
                }
                setBody(json.encodeToString(CreateAuditEventRequest.serializer(), event))
            }
        } catch (e: Exception) {
            throw UpstreamUnavailableException("black-box-service unavailable", e)
        }
    }

    override suspend fun listEvents(
        orgId: String,
        userId: String?,
        limit: Int,
    ): UpstreamResult {
        try {
            val q =
                buildString {
                    append("orgId=").append(orgId)
                    append("&limit=").append(limit)
                    if (!userId.isNullOrBlank()) append("&userId=").append(userId)
                }
            val response: HttpResponse =
                client.request("${baseUrl.trimEnd('/')}/events?$q") {
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
            throw UpstreamUnavailableException("black-box-service unavailable", e)
        }
    }
}

private val auditScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val auditLog = LoggerFactory.getLogger("pro.masterdoc.gateway.audit")

fun BlackBoxClient.recordAsync(event: CreateAuditEventRequest) {
    val client = this
    auditScope.launch {
        try {
            client.postEvent(event)
        } catch (e: Exception) {
            auditLog.warn("black-box audit failed: {}", e.message)
        }
    }
}

fun summarizeBody(bytes: ByteArray?, max: Int = 2048): String? {
    if (bytes == null || bytes.isEmpty()) return null
    val text = bytes.decodeToString()
    return if (text.length <= max) text else text.take(max) + "…"
}
