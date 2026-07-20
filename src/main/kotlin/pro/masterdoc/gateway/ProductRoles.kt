package pro.masterdoc.gateway

object ProductRoles {
    val ALL: Set<String> =
        setOf("admin", "dispatcher", "engineer", "requester", "reporter", "technologist")

    /** @return error message or null if valid */
    fun validate(roles: List<String>): String? {
        if (roles.isEmpty()) return "roles must not be empty"
        for (r in roles) {
            if (r !in ALL) return "Unknown role: $r"
        }
        return null
    }
}
