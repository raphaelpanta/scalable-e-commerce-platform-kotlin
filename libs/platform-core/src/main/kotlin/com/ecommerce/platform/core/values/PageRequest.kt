package com.ecommerce.platform.core.values

import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import com.ecommerce.platform.core.result.Validated
import com.ecommerce.platform.core.result.ValidationError

/**
 * A page of a listing: 0-based [page] and a [size] of 1..100, default 20 (service conventions section 3). Responses
 * carry `{items, page, size, totalItems}`.
 */
@ConsistentCopyVisibility
data class PageRequest private constructor(
    val page: Int,
    val size: Int,
) {
    /** Rows to skip: `page * size`, as a `Long` so that large pages cannot overflow. */
    val offset: Long get() = page.toLong() * size

    companion object {
        const val DEFAULT_SIZE: Int = 20
        const val MAX_SIZE: Int = 100

        /** The first page with the default size. */
        val FIRST: PageRequest = PageRequest(0, DEFAULT_SIZE)

        /** Validates [page] (>= 0) and [size] (1..100). */
        fun of(
            page: Int = 0,
            size: Int = DEFAULT_SIZE,
        ): Validated<PageRequest> =
            when {
                page < 0 -> ValidationError("page", "must be at least 0").left()
                size < 1 -> ValidationError("size", "must be at least 1").left()
                size > MAX_SIZE -> ValidationError("size", "must be at most $MAX_SIZE").left()
                else -> PageRequest(page, size).right()
            }

        /** Parses the `page` and `size` query parameters; absent parameters take their defaults. */
        fun fromQuery(
            page: String?,
            size: String?,
        ): Validated<PageRequest> =
            integer(page, "page", 0).flatMap { p -> integer(size, "size", DEFAULT_SIZE).flatMap { s -> of(p, s) } }

        private fun integer(
            raw: String?,
            field: String,
            default: Int,
        ): Validated<Int> =
            if (raw == null) {
                default.right()
            } else {
                raw.toIntOrNull()?.right() ?: ValidationError(field, "must be an integer").left()
            }
    }
}
