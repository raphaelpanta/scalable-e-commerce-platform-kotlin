package com.ecommerce.gateway.correlation

import java.util.UUID

/**
 * Correlation id rules of contracts/gateway-routes.md ("Correlation id"): a client value is accepted when it is a
 * UUID or a 16 to 64 character token of `[A-Za-z0-9-]`; a missing value is generated; a malformed or oversized value
 * is replaced by a generated one and kept (shortened) for the access log field `originalCorrelationId`.
 */
object CorrelationIds {
    const val HEADER = "X-Correlation-Id"
    const val CONTEXT_KEY = "correlationId"
    const val ORIGINAL_LOG_FIELD = "originalCorrelationId"

    /** At most this many characters of a refused value are logged. */
    const val MAX_LOGGED_ORIGINAL = 128

    private val UUID_FORM = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val TOKEN_FORM = Regex("^[A-Za-z0-9-]{16,64}$")

    fun isAcceptable(candidate: String): Boolean = UUID_FORM.matches(candidate) || TOKEN_FORM.matches(candidate)

    /** Decides the id of a request from the client's header value (null when absent). */
    fun resolve(
        candidate: String?,
        generate: () -> String = ::newId,
    ): Resolution =
        when {
            candidate.isNullOrEmpty() -> Resolution(generate(), original = null)
            isAcceptable(candidate) -> Resolution(candidate, original = null)
            else -> Resolution(generate(), original = candidate.take(MAX_LOGGED_ORIGINAL))
        }

    fun newId(): String = UUID.randomUUID().toString()

    /** The id used for the request and, when a client value was refused, that value for investigation. */
    data class Resolution(
        val id: String,
        val original: String?,
    ) {
        val replaced: Boolean get() = original != null
    }
}
