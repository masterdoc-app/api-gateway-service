package pro.masterdoc.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ZitadelListQuery(
    val offset: Long = 0,
    val limit: Int = 100,
    val asc: Boolean = true,
)

@Serializable
internal data class ZitadelSearchDetails(
    @SerialName("totalResult") val totalResult: Long? = null,
)

@Serializable
internal data class ZitadelCreateHumanResponse(
    @SerialName("userId") val userId: String? = null,
)

@Serializable
internal data class ZitadelUsersSearchResponse(
    val details: ZitadelSearchDetails? = null,
    val result: List<ZitadelManagementUser> = emptyList(),
)

@Serializable
internal data class ZitadelManagementUser(
    val id: String? = null,
    val state: String? = null,
    val human: ZitadelHuman? = null,
)

@Serializable
internal data class ZitadelHuman(
    val profile: ZitadelHumanProfile? = null,
    val email: ZitadelHumanEmail? = null,
)

@Serializable
internal data class ZitadelHumanProfile(
    @SerialName("givenName") val givenName: String? = null,
    @SerialName("familyName") val familyName: String? = null,
    @SerialName("firstName") val firstName: String? = null,
    @SerialName("lastName") val lastName: String? = null,
) {
    fun resolvedGivenName(): String = givenName ?: firstName ?: ""

    fun resolvedFamilyName(): String = familyName ?: lastName ?: ""
}

@Serializable
internal data class ZitadelHumanEmail(
    val email: String? = null,
    @SerialName("isEmailVerified") val isEmailVerified: Boolean? = null,
)

@Serializable
internal data class ZitadelV2GetUserResponse(
    val user: ZitadelV2User? = null,
)

@Serializable
internal data class ZitadelV2User(
    @SerialName("userId") val userId: String? = null,
    val state: String? = null,
    val human: ZitadelV2Human? = null,
)

@Serializable
internal data class ZitadelV2Human(
    val profile: ZitadelHumanProfile? = null,
    val email: ZitadelV2Email? = null,
)

@Serializable
internal data class ZitadelV2Email(
    val email: String? = null,
    @SerialName("isVerified") val isVerified: Boolean? = null,
)

@Serializable
internal data class ZitadelGrantsSearchResponse(
    val result: List<ZitadelUserGrant> = emptyList(),
)

@Serializable
internal data class ZitadelUserGrant(
    val id: String? = null,
    @SerialName("userId") val userId: String? = null,
    @SerialName("roleKeys") val roleKeys: List<String> = emptyList(),
    @SerialName("projectId") val projectId: String? = null,
    @SerialName("firstName") val firstName: String? = null,
    @SerialName("lastName") val lastName: String? = null,
    val email: String? = null,
)

@Serializable
internal data class ZitadelCreateGrantRequest(
    @SerialName("projectId") val projectId: String,
    @SerialName("roleKeys") val roleKeys: List<String>,
)

@Serializable
internal data class ZitadelUpdateGrantRequest(
    @SerialName("roleKeys") val roleKeys: List<String>,
)

internal object ZitadelAdminMapping {
    fun toAdminUser(
        user: ZitadelManagementUser,
        features: List<String>,
        includeInviteSent: Boolean,
        inviteSent: Boolean = true,
    ): AdminUser {
        val human = user.human
        val profile = human?.profile
        return AdminUser(
            id = user.id.orEmpty(),
            email = human?.email?.email.orEmpty(),
            givenName = profile?.resolvedGivenName().orEmpty(),
            familyName = profile?.resolvedFamilyName().orEmpty(),
            features = features,
            state = UserStateMapper.fromZitadel(user.state, human?.email?.isEmailVerified),
            inviteSent = inviteSent.takeIf { includeInviteSent },
        )
    }

    fun toAdminUserFromGrant(
        grant: ZitadelUserGrant,
        state: String,
        includeInviteSent: Boolean,
        inviteSent: Boolean = true,
    ): AdminUser =
        AdminUser(
            id = grant.userId.orEmpty(),
            email = grant.email.orEmpty(),
            givenName = grant.firstName.orEmpty(),
            familyName = grant.lastName.orEmpty(),
            features = grant.roleKeys,
            state = state,
            inviteSent = inviteSent.takeIf { includeInviteSent },
        )
}
