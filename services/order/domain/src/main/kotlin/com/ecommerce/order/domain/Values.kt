package com.ecommerce.order.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * An amount of money in minor units of an ISO-4217 [currency] (data-model section 2): never negative, arithmetic
 * only between equal currencies, overflow rejected. The order context keeps its own copy (Principle VI).
 */
data class Money(
    val amountMinor: Long,
    val currency: String,
) {
    init {
        require(amountMinor >= 0) { "money must not be negative, was $amountMinor" }
        require(CURRENCY.matches(currency)) { "currency must be an ISO-4217 code, was '$currency'" }
    }

    /** The sum of this and [other], which must have the same currency. */
    operator fun plus(other: Money): Money {
        require(other.currency == currency) { "cannot add $currency and ${other.currency}" }
        return Money(Math.addExact(amountMinor, other.amountMinor), currency)
    }

    /** This amount [quantity] times. */
    operator fun times(quantity: Quantity): Money = Money(Math.multiplyExact(amountMinor, quantity.value), currency)

    companion object {
        private val CURRENCY = Regex("[A-Z]{3}")

        /** Validates [amountMinor] and [currency] of an input named [field]. */
        fun of(
            amountMinor: Long,
            currency: String,
            field: String = "amount",
        ): Either<OrderError.Invalid, Money> =
            when {
                amountMinor < 0 -> OrderError.Invalid(field, "must not be negative").left()
                !CURRENCY.matches(currency) -> OrderError.Invalid(field, "currency must be an ISO-4217 code").left()
                else -> Money(amountMinor, currency).right()
            }

        /** Zero in [currency]. */
        fun zero(currency: String): Money = Money(0, currency)
    }
}

/** Quantity of one order line: 1..99 (data-model section 2). */
@JvmInline
value class Quantity(
    val value: Int,
) {
    init {
        require(value in MIN..MAX) { "quantity must be between $MIN and $MAX, was $value" }
    }

    companion object {
        const val MIN: Int = 1
        const val MAX: Int = 99

        /** Validates [value]. */
        fun of(value: Int): Either<OrderError.Invalid, Quantity> =
            if (value in MIN..MAX) {
                Quantity(value).right()
            } else {
                OrderError.Invalid("quantity", "must be between $MIN and $MAX").left()
            }
    }
}

/** Human-readable, unique and immutable order number `ORD-<yyyyMMdd>-<sequence>` (service conventions section 8). */
@JvmInline
value class OrderNumber(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "order number must look like ORD-20261002-0001, was '$value'" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("ORD-\\d{8}-\\d{4,}")
        private const val SEQUENCE_DIGITS = 4

        /** The number of the [sequence]-th order (1-based) placed on [day]. */
        fun of(
            day: LocalDate,
            sequence: Long,
        ): OrderNumber {
            require(sequence >= 1) { "the sequence starts at 1, was $sequence" }
            val digits = sequence.toString().padStart(SEQUENCE_DIGITS, '0')
            return OrderNumber("ORD-${day.format(DateTimeFormatter.BASIC_ISO_DATE)}-$digits")
        }
    }
}

/** One page of a listing: `page` is 0-based, `size` 1..100 (service conventions section 3). */
data class PageRequest(
    val page: Int,
    val size: Int,
) {
    /** Rows skipped before this page. */
    val offset: Long get() = page.toLong() * size

    companion object {
        const val DEFAULT_SIZE: Int = 20
        const val MAX_SIZE: Int = 100

        /** Validates the paging parameters. */
        fun of(
            page: Int,
            size: Int,
        ): Either<OrderError.Invalid, PageRequest> =
            when {
                page < 0 -> OrderError.Invalid("page", "must be 0 or more").left()
                size !in 1..MAX_SIZE -> OrderError.Invalid("size", "must be between 1 and $MAX_SIZE").left()
                else -> PageRequest(page, size).right()
            }
    }
}

/** A page of items with the total number of matching items. */
data class Page<out T>(
    val items: List<T>,
    val request: PageRequest,
    val totalItems: Long,
)
