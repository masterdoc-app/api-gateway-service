package pro.masterdoc.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

interface ZitadelAdminClient {
    suspend fun inviteUser(request: InviteUserRequest): AdminUser

    suspend fun listUsers(limit: Int, offset: Int): AdminUserList

    suspend fun setRoles(userId: String, roles: List<String>): AdminUser

    suspend fun resendInvite(userId: String)

    companion object {
        fun unconfigured(): ZitadelAdminClient =
            object : ZitadelAdminClient {
                private fun fail(): Nothing =
                    throw UpstreamUnavailableException("zitadel admin not configured")

                override suspend fun inviteUser(request: InviteUserRequest): AdminUser = fail()

                override suspend fun listUsers(limit: Int, offset: Int): AdminUserList = fail()

                override suspend fun setRoles(userId: String, roles: List<String>): AdminUser = fail()

                override suspend fun resendInvite(userId: String) = fail()
            }

        fun http(config: GatewayConfig): ZitadelAdminClient {
            if (config.zitadelMgmtToken.isBlank()) {
                return tokenNotConfiguredClient()
            }
            return HttpZitadelAdminClient(config)
        }

        private fun tokenNotConfiguredClient(): ZitadelAdminClient =
            object : ZitadelAdminClient {
                private fun fail(): Nothing =
                    throw ZitadelAdminException.Upstream("ZITADEL_MGMT_TOKEN not set")

                override suspend fun inviteUser(request: InviteUserRequest): AdminUser = fail()

                override suspend fun listUsers(limit: Int, offset: Int): AdminUserList = fail()

                override suspend fun setRoles(userId: String, roles: List<String>): AdminUser = fail()

                override suspend fun resendInvite(userId: String) = fail()
            }
    }
}

class HttpZitadelAdminClient(
    private val config: GatewayConfig,
    private val client: HttpClient = defaultClient(),
) : ZitadelAdminClient {
    private val baseUrl = config.zitadelIssuer.trimEnd('/')

    override suspend fun inviteUser(request: InviteUserRequest): AdminUser {
        val createBody =
            buildJsonObject {
                putJsonObject("profile") {
                    put("givenName", request.givenName)
                    put("familyName", request.familyName)
                }
                putJsonObject("email") {
                    put("email", request.email)
                    putJsonObject("sendCode") {}
                }
            }
        val createResponse = postJson("$baseUrl/v2/users/human", createBody.toString())
        ensureSuccess(createResponse) { status, body ->
            when (status) {
                HttpStatusCode.Conflict -> throw ZitadelAdminException.Conflict(extractMessage(body, "email already registered"))
                else -> mapHttpError(status, body)
            }
        }
        val created = json.decodeFromString<ZitadelCreateHumanResponse>(createResponse.body)
        val userId =
            created.userId?.takeIf { it.isNotBlank() }
                ?: throw ZitadelAdminException.Upstream("zitadel create user response missing userId")

        val grantResponse =
            postJson(
                "$baseUrl/management/v1/users/$userId/grants",
                json.encodeToString(ZitadelCreateGrantRequest.serializer(), ZitadelCreateGrantRequest(config.zitadelProjectId, request.roles)),
            )
        ensureSuccess(grantResponse)

        return fetchUser(userId, includeInviteSent = true)
    }

    override suspend fun listUsers(limit: Int, offset: Int): AdminUserList {
        val searchBody =
            buildJsonObject {
                putJsonObject("query") {
                    put("offset", offset.toLong())
                    put("limit", limit)
                    put("asc", true)
                }
            }
        val usersResponse = postJson("$baseUrl/management/v1/users/_search", searchBody.toString())
        ensureSuccess(usersResponse)
        val usersSearch = json.decodeFromString<ZitadelUsersSearchResponse>(usersResponse.body)
        val grantsByUserId = loadProjectGrantsByUserId()

        val items =
            usersSearch.result.map { user ->
                val roles = grantsByUserId[user.id.orEmpty()].orEmpty()
                ZitadelAdminMapping.toAdminUser(user, roles, includeInviteSent = false)
            }
        val total = usersSearch.details?.totalResult?.toInt() ?: items.size
        return AdminUserList(items = items, total = total)
    }

    override suspend fun setRoles(userId: String, roles: List<String>): AdminUser {
        val grant = findUserGrant(userId)
        if (grant?.id != null) {
            val updateResponse =
                putJson(
                    "$baseUrl/management/v1/users/$userId/grants/${grant.id}",
                    json.encodeToString(ZitadelUpdateGrantRequest.serializer(), ZitadelUpdateGrantRequest(roles)),
                )
            ensureSuccess(updateResponse)
        } else {
            val createResponse =
                postJson(
                    "$baseUrl/management/v1/users/$userId/grants",
                    json.encodeToString(ZitadelCreateGrantRequest.serializer(), ZitadelCreateGrantRequest(config.zitadelProjectId, roles)),
                )
            ensureSuccess(createResponse)
        }
        return fetchUser(userId, includeInviteSent = false)
    }

    override suspend fun resendInvite(userId: String) {
        val user = findUser(userId) ?: throw ZitadelAdminException.NotFound("user not found")
        if (UserStateMapper.fromZitadel(user.state) == "active") {
            throw ZitadelAdminException.Conflict("user is already active")
        }
        val body =
            buildJsonObject {
                putJsonObject("sendCode") {}
            }
        val inviteResponse = postJson("$baseUrl/v2/users/$userId/invite_code", body.toString())
        ensureSuccess(inviteResponse)
    }

    private suspend fun fetchUser(userId: String, includeInviteSent: Boolean): AdminUser {
        val user =
            findUser(userId)
                ?: throw ZitadelAdminException.NotFound("user not found")
        val grant = findUserGrant(userId)
        return ZitadelAdminMapping.toAdminUser(
            user = user,
            roles = grant?.roleKeys.orEmpty(),
            includeInviteSent = includeInviteSent,
        )
    }

    private suspend fun findUser(userId: String): ZitadelManagementUser? {
        val searchBody =
            buildJsonObject {
                putJsonObject("query") {
                    put("offset", 0)
                    put("limit", 1)
                    put("asc", true)
                }
                putJsonArray("queries") {
                    add(
                        buildJsonObject {
                            putJsonObject("userIdQuery") {
                                put("userId", userId)
                            }
                        },
                    )
                }
            }
        val response = postJson("$baseUrl/management/v1/users/_search", searchBody.toString())
        ensureSuccess(response)
        return json.decodeFromString<ZitadelUsersSearchResponse>(response.body).result.firstOrNull()
    }

    private suspend fun findUserGrant(userId: String): ZitadelUserGrant? {
        val searchBody =
            buildJsonObject {
                putJsonObject("query") {
                    put("offset", 0)
                    put("limit", 1)
                    put("asc", true)
                }
                putJsonArray("queries") {
                    add(
                        buildJsonObject {
                            putJsonObject("projectIdQuery") {
                                put("projectId", config.zitadelProjectId)
                            }
                        },
                    )
                    add(
                        buildJsonObject {
                            putJsonObject("userIdQuery") {
                                put("userId", userId)
                            }
                        },
                    )
                }
            }
        val response = postJson("$baseUrl/management/v1/users/grants/_search", searchBody.toString())
        ensureSuccess(response)
        return json.decodeFromString<ZitadelGrantsSearchResponse>(response.body).result.firstOrNull()
    }

    private suspend fun loadProjectGrantsByUserId(): Map<String, List<String>> {
        val searchBody =
            buildJsonObject {
                putJsonObject("query") {
                    put("offset", 0)
                    put("limit", 1000)
                    put("asc", true)
                }
                putJsonArray("queries") {
                    add(
                        buildJsonObject {
                            putJsonObject("projectIdQuery") {
                                put("projectId", config.zitadelProjectId)
                            }
                        },
                    )
                }
            }
        val response = postJson("$baseUrl/management/v1/users/grants/_search", searchBody.toString())
        ensureSuccess(response)
        val grants = json.decodeFromString<ZitadelGrantsSearchResponse>(response.body).result
        return grants
            .groupBy { it.userId.orEmpty() }
            .mapValues { (_, userGrants) -> userGrants.flatMap { it.roleKeys }.distinct() }
    }

    private data class HttpTextResponse(val status: HttpStatusCode, val body: String)

    private suspend fun postJson(url: String, body: String): HttpTextResponse =
        try {
            val response =
                client.post(url) {
                    applyAuthHeaders()
                    header(HttpHeaders.ContentType, "application/json")
                    header(HttpHeaders.Accept, "application/json")
                    setBody(body)
                }
            HttpTextResponse(response.status, response.bodyAsText())
        } catch (e: ZitadelAdminException) {
            throw e
        } catch (e: Exception) {
            throw ZitadelAdminException.Upstream("zitadel admin request failed: ${e.message}")
        }

    private suspend fun putJson(url: String, body: String): HttpTextResponse =
        try {
            val response =
                client.put(url) {
                    applyAuthHeaders()
                    header(HttpHeaders.ContentType, "application/json")
                    header(HttpHeaders.Accept, "application/json")
                    setBody(body)
                }
            HttpTextResponse(response.status, response.bodyAsText())
        } catch (e: ZitadelAdminException) {
            throw e
        } catch (e: Exception) {
            throw ZitadelAdminException.Upstream("zitadel admin request failed: ${e.message}")
        }

    private fun ensureSuccess(
        response: HttpTextResponse,
        onError: (HttpStatusCode, String) -> Nothing = { status, body -> mapHttpError(status, body) },
    ) {
        if (!response.status.isSuccess()) {
            onError(response.status, response.body)
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyAuthHeaders() {
        header(HttpHeaders.Authorization, "Bearer ${config.zitadelMgmtToken}")
        if (config.zitadelOrgId.isNotBlank()) {
            header("x-zitadel-orgid", config.zitadelOrgId)
        }
    }

    private fun mapHttpError(status: HttpStatusCode, body: String): Nothing {
        val message = extractMessage(body, status.description)
        throw when (status) {
            HttpStatusCode.Conflict -> ZitadelAdminException.Conflict(message)
            HttpStatusCode.NotFound -> ZitadelAdminException.NotFound(message)
            HttpStatusCode.BadRequest -> ZitadelAdminException.BadRequest(message)
            else -> ZitadelAdminException.Upstream("zitadel returned ${status.value}: $message")
        }
    }

    private fun extractMessage(body: String, fallback: String): String {
        if (body.isBlank()) return fallback
        return runCatching {
            json.decodeFromString<ZitadelErrorResponse>(body).message?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: body.take(500)
    }

    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        private fun defaultClient(): HttpClient =
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(json)
                }
            }
    }
}

@Serializable
private data class ZitadelErrorResponse(val message: String? = null)

class FakeZitadelAdminClient : ZitadelAdminClient {
    private val usersById = linkedMapOf<String, StoredUser>()
    private val idByEmail = mutableMapOf<String, String>()
    private var nextId = 1L

    override suspend fun inviteUser(request: InviteUserRequest): AdminUser {
        val emailKey = request.email.lowercase()
        if (emailKey in idByEmail) {
            throw ZitadelAdminException.Conflict("email already registered")
        }
        val id = "fake-user-$nextId"
        nextId += 1
        val user =
            StoredUser(
                id = id,
                email = request.email,
                givenName = request.givenName,
                familyName = request.familyName,
                roles = request.roles.toMutableList(),
                state = "invited",
                inviteSent = true,
            )
        usersById[id] = user
        idByEmail[emailKey] = id
        return user.toAdminUser(includeInviteSent = true)
    }

    override suspend fun listUsers(limit: Int, offset: Int): AdminUserList {
        val all = usersById.values.map { it.toAdminUser(includeInviteSent = false) }
        val items = all.drop(offset).take(limit)
        return AdminUserList(items = items, total = all.size)
    }

    override suspend fun setRoles(userId: String, roles: List<String>): AdminUser {
        val user =
            usersById[userId]
                ?: throw ZitadelAdminException.NotFound("user not found")
        user.roles.clear()
        user.roles.addAll(roles)
        return user.toAdminUser(includeInviteSent = false)
    }

    override suspend fun resendInvite(userId: String) {
        val user =
            usersById[userId]
                ?: throw ZitadelAdminException.NotFound("user not found")
        if (user.state == "active") {
            throw ZitadelAdminException.Conflict("user is already active")
        }
        user.inviteSent = true
    }

    private data class StoredUser(
        val id: String,
        val email: String,
        val givenName: String,
        val familyName: String,
        val roles: MutableList<String>,
        val state: String,
        var inviteSent: Boolean,
    ) {
        fun toAdminUser(includeInviteSent: Boolean): AdminUser =
            AdminUser(
                id = id,
                email = email,
                givenName = givenName,
                familyName = familyName,
                roles = roles.toList(),
                state = state,
                inviteSent = inviteSent.takeIf { includeInviteSent },
            )
    }
}
