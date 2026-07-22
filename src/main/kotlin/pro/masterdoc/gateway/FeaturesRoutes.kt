package pro.masterdoc.gateway

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.installFeaturesRoutes(deps: GatewayDeps) {
    routing {
        get("/features") {
            val authorization = call.request.header(HttpHeaders.Authorization)
            if (authorization.isNullOrBlank() || !authorization.startsWith("Bearer ")) {
                call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                return@get
            }
            val token = authorization.removePrefix("Bearer ").trim()
            if (token.isEmpty() || deps.tokenValidator.validate(token) == null) {
                call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                return@get
            }
            call.respond(ProductFeatures.catalog())
        }
    }
}
