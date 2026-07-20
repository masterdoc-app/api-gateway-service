package pro.masterdoc.gateway

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
    }
}

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
