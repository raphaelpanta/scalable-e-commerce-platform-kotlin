package com.ecommerce.catalog.domain

/**
 * The name of a service in the platform: kebab-case, 3 to 40 characters, starting with a lowercase letter
 * and ending with a lowercase letter or digit. Only [of] creates one, so every instance is valid.
 */
@JvmInline
value class ServiceName private constructor(
    val value: String,
) {
    companion object {
        private val PATTERN = Regex("[a-z][a-z0-9-]{1,38}[a-z0-9]")

        /** Validates [raw] and returns either the name or the reason it was rejected. */
        fun of(raw: String): ServiceNameResult =
            if (PATTERN.matches(raw)) {
                ServiceNameResult.Valid(ServiceName(raw))
            } else {
                ServiceNameResult.Invalid(
                    "service name '$raw' must be kebab-case, 3 to 40 characters, start with a lowercase letter " +
                        "and end with a lowercase letter or digit",
                )
            }
    }
}

/** Outcome of [ServiceName.of]. */
sealed interface ServiceNameResult {
    data class Valid(
        val name: ServiceName,
    ) : ServiceNameResult

    data class Invalid(
        val reason: String,
    ) : ServiceNameResult
}
