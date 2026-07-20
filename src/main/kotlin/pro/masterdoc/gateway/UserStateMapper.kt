package pro.masterdoc.gateway

object UserStateMapper {
    fun fromZitadel(state: String?): String =
        when (state) {
            "USER_STATE_INITIAL" -> "invited"
            "USER_STATE_ACTIVE" -> "active"
            "USER_STATE_INACTIVE", "USER_STATE_LOCKED", "USER_STATE_DELETED" -> "inactive"
            else -> "inactive"
        }
}
