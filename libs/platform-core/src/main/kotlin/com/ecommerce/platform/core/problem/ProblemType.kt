package com.ecommerce.platform.core.problem

import java.net.URI

private const val PROBLEM_TYPE_BASE_URI = "https://ecommerce.example/problems/"

/**
 * The catalogue of platform problem types (service conventions section 3): type URI
 * `https://ecommerce.example/problems/<slug>`, default status and default title as used by the OpenAPI and Pact
 * contracts. Service-specific types (for example `order-not-cancellable`) are built with [Problem.custom].
 */
enum class ProblemType(
    val slug: String,
    val status: Int,
    val title: String,
) {
    /** Per-field validation failure (422 `Validation failed`) or malformed request (400 `Bad request`). */
    VALIDATION(slug = "validation", status = 422, title = "Validation failed"),
    NOT_FOUND(slug = "not-found", status = 404, title = "Not found"),
    CONFLICT(slug = "conflict", status = 409, title = "Conflict"),
    THROTTLED(slug = "throttled", status = 429, title = "Too many requests"),
    INSUFFICIENT_STOCK(slug = "insufficient-stock", status = 409, title = "Insufficient stock"),
    PRICE_CHANGED(slug = "price-changed", status = 409, title = "Price changed"),
    STALE_REVISION(slug = "stale-revision", status = 409, title = "Stale revision"),
    UNAUTHORIZED(slug = "unauthorized", status = 401, title = "Unauthorized"),
    FORBIDDEN(slug = "forbidden", status = 403, title = "Forbidden"),
    UNAVAILABLE(slug = "unavailable", status = 503, title = "Service unavailable"),
    ;

    /** `https://ecommerce.example/problems/<slug>`. */
    val uri: URI = URI.create(PROBLEM_TYPE_BASE_URI + slug)

    /** The title for [status]: the default title, except `Bad request` for a 400 validation problem. */
    fun titleFor(status: Int): String = if (this == VALIDATION && status == BAD_REQUEST) BAD_REQUEST_TITLE else title

    companion object {
        /** Base of every platform problem type URI. */
        const val BASE_URI: String = PROBLEM_TYPE_BASE_URI

        /** RFC 9457 type of problems that carry no more semantics than their status. */
        val ABOUT_BLANK: URI = URI.create("about:blank")

        private const val BAD_REQUEST = 400
        private const val BAD_REQUEST_TITLE = "Bad request"
        private const val UNPROCESSABLE = 422
        private val BY_STATUS: Map<Int, ProblemType> =
            mapOf(
                BAD_REQUEST to VALIDATION,
                UNPROCESSABLE to VALIDATION,
                UNAUTHORIZED.status to UNAUTHORIZED,
                FORBIDDEN.status to FORBIDDEN,
                NOT_FOUND.status to NOT_FOUND,
                CONFLICT.status to CONFLICT,
                THROTTLED.status to THROTTLED,
                UNAVAILABLE.status to UNAVAILABLE,
            )

        /** The type URI of [slug]. */
        fun uriOf(slug: String): URI = URI.create(BASE_URI + slug)

        /** The catalogued type with [slug], if any. */
        fun fromSlug(slug: String): ProblemType? = entries.firstOrNull { it.slug == slug }

        /** The catalogued type with type URI [uri], if any. */
        fun fromUri(uri: URI): ProblemType? = entries.firstOrNull { it.uri == uri }

        /** The generic catalogued type for an HTTP [status] (409 maps to `conflict`), if any. */
        fun forStatus(status: Int): ProblemType? = BY_STATUS[status]
    }
}
