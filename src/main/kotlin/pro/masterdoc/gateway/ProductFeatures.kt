package pro.masterdoc.gateway

object ProductFeatures {
    val ALL: Set<String> = setOf("board", "copilot", "charts", "equipment", "user_invite")

    /** @return error message or null if valid */
    fun validate(features: List<String>): String? {
        if (features.isEmpty()) return "features must not be empty"
        for (f in features) {
            if (f !in ALL) return "Unknown feature: $f"
        }
        return null
    }
}
