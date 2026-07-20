package pro.masterdoc.gateway

object UserStateMapper {
    fun fromZitadel(state: String?, emailVerified: Boolean? = null): String =
        when (state) {
            "USER_STATE_INACTIVE", "USER_STATE_LOCKED", "USER_STATE_DELETED" -> "inactive"
            "USER_STATE_INITIAL" -> "invited"
            "USER_STATE_ACTIVE" -> if (emailVerified == true) "active" else "invited"
            else -> "inactive"
        }
}
