package pro.masterdoc.gateway

import kotlinx.serialization.Serializable

@Serializable
data class InviteUserRequest(
    val email: String,
    val givenName: String,
    val familyName: String,
    val roles: List<String>,
)

@Serializable
data class SetRolesRequest(val roles: List<String>)

@Serializable
data class AdminUser(
    val id: String,
    val email: String,
    val givenName: String,
    val familyName: String,
    val roles: List<String>,
    val state: String,
    val inviteSent: Boolean? = null,
)

@Serializable
data class AdminUserList(val items: List<AdminUser>, val total: Int)

sealed class ZitadelAdminException(message: String) : Exception(message) {
    class Conflict(message: String) : ZitadelAdminException(message)

    class NotFound(message: String) : ZitadelAdminException(message)

    class BadRequest(message: String) : ZitadelAdminException(message)

    class Upstream(message: String) : ZitadelAdminException(message)
}
