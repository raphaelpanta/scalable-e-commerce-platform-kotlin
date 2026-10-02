package com.ecommerce.catalog.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.util.Locale

/** A page of a listing: 0-based [page] and a [size] of 1..100 (service conventions section 3). */
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

        fun of(
            page: Int = 0,
            size: Int = DEFAULT_SIZE,
        ): Either<FieldIssue, PageRequest> =
            when {
                page < 0 -> FieldIssue("page", "must be at least 0").left()
                size < 1 -> FieldIssue("size", "must be at least 1").left()
                size > MAX_SIZE -> FieldIssue("size", "must be at most $MAX_SIZE").left()
                else -> PageRequest(page, size).right()
            }
    }
}

/** One page of [items] out of [totalItems] (`{items, page, size, totalItems}`). */
data class Page<out T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
) {
    /** The same page with every item transformed. */
    fun <R> map(transform: (T) -> R): Page<R> = Page(items.map(transform), page, size, totalItems)
}

/**
 * A free-text search term (catalog.yaml `q`): trimmed, 1..100 characters, compared case-insensitively. Relevance
 * ([rank], lower is better): the whole name, then a name prefix, then anywhere in the name, then the description.
 */
@JvmInline
value class SearchTerm private constructor(
    val value: String,
) {
    /** The rank of a product named [name] with [description], or null when the term matches neither. */
    fun rank(
        name: String,
        description: String?,
    ): Int? {
        val lowerName = name.lowercase(Locale.ROOT)
        return when {
            lowerName == value -> EXACT
            lowerName.startsWith(value) -> PREFIX
            lowerName.contains(value) -> IN_NAME
            description?.lowercase(Locale.ROOT)?.contains(value) == true -> IN_DESCRIPTION
            else -> null
        }
    }

    companion object {
        const val MAX: Int = 100
        const val EXACT: Int = 0
        const val PREFIX: Int = 1
        const val IN_NAME: Int = 2
        const val IN_DESCRIPTION: Int = 3

        fun of(raw: String): Either<FieldIssue, SearchTerm> =
            Text.bounded(raw, "q", 1..MAX).map { SearchTerm(it.lowercase(Locale.ROOT)) }
    }
}
