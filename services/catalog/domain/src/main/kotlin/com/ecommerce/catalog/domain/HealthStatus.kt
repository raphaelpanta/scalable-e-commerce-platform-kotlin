package com.ecommerce.catalog.domain

/** Whether a service, or one of the things it depends on, can do its work. */
sealed interface HealthStatus {
    data object Up : HealthStatus

    data class Down(
        val reason: String,
    ) : HealthStatus {
        init {
            require(reason.isNotBlank()) { "a down status needs a reason" }
        }
    }

    companion object {
        /** A [Down] status; [reason] must not be blank. */
        fun down(reason: String): Down = Down(reason)

        /** [Up] when every status is up, otherwise [Down] with the failing reasons joined by "; " in order. */
        fun combine(statuses: List<HealthStatus>): HealthStatus {
            val reasons = statuses.filterIsInstance<Down>().map(Down::reason)
            return if (reasons.isEmpty()) Up else Down(reasons.joinToString(separator = "; "))
        }
    }
}
