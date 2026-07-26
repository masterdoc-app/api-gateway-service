package pro.masterdoc.gateway

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.util.AttributeKey
import java.util.UUID
import org.slf4j.MDC
import org.slf4j.event.Level

val RequestIdKey = AttributeKey<String>("RequestId")

fun Application.installObservability(config: GatewayConfig) {
    install(CallLogging) {
        level = Level.INFO
        mdc(RequestIdKey.name) { call -> call.attributes.getOrNull(RequestIdKey) }
        format { call ->
            val requestId = call.attributes.getOrNull(RequestIdKey) ?: "-"
            val status = call.response.status()?.value ?: 0
            "${call.request.httpMethod.value} ${call.request.path()} status=$status requestId=$requestId"
        }
    }
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Request-Id")
        config.corsOrigins.forEach { raw ->
            val withoutScheme = raw.removePrefix("https://").removePrefix("http://")
            val host = withoutScheme.substringBefore("/")
            val schemes =
                when {
                    raw.startsWith("https://") -> listOf("https")
                    raw.startsWith("http://") -> listOf("http")
                    else -> listOf("http", "https")
                }
            allowHost(host, schemes = schemes)
        }
    }
    intercept(ApplicationCallPipeline.Setup) {
        val incoming = call.request.header("X-Request-Id")?.takeIf { it.isNotBlank() }
        val requestId = incoming ?: UUID.randomUUID().toString()
        call.attributes.put(RequestIdKey, requestId)
        call.response.header("X-Request-Id", requestId)
        MDC.put("requestId", requestId)
        try {
            proceed()
        } finally {
            MDC.remove("requestId")
        }
    }
}
