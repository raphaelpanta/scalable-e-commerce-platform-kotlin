package com.ecommerce.payment.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/**
 * An amount of money in minor units of an ISO-4217 [currency] (data-model section 2): never negative, compared by
 * value. The payment context keeps its own copy (Principle VI); charges and refunds are strictly positive.
 */
data class Money(
    val amountMinor: Long,
    val currency: String,
) {
    init {
        require(amountMinor >= 0) { "money must not be negative, was $amountMinor" }
        require(CURRENCY.matches(currency)) { "currency must be an ISO-4217 code, was '$currency'" }
    }

    companion object {
        private val CURRENCY = Regex("[A-Z]{3}")

        /** A strictly positive amount (`PositiveMoney` of payment-internal.yaml) for the input named [field]. */
        fun positive(
            amountMinor: Long,
            currency: String,
            field: String = "amount",
        ): Either<PaymentError.Invalid, Money> =
            when {
                amountMinor < 1 -> PaymentError.Invalid("$field.amountMinor", "must be at least 1").left()

                !CURRENCY.matches(
                    currency,
                ) -> PaymentError.Invalid("$field.currency", "must be an ISO-4217 code").left()

                else -> Money(amountMinor, currency).right()
            }
    }
}

/**
 * The opaque token of the simulated provider that stands for the shopper's payment method (Principle III): never a
 * card number, 1 to 128 characters. Its [toString] never reveals the token, so it cannot leak into a log line.
 */
@ConsistentCopyVisibility
data class PaymentMethodRef private constructor(
    val token: String,
) {
    override fun toString(): String = "PaymentMethodRef(***)"

    companion object {
        const val MAX_LENGTH: Int = 128

        /** Validates [token]. */
        fun of(token: String): Either<PaymentError.Invalid, PaymentMethodRef> =
            if (token.length in 1..MAX_LENGTH) {
                PaymentMethodRef(token).right()
            } else {
                PaymentError.Invalid("paymentMethodRef", "must be 1 to $MAX_LENGTH characters").left()
            }
    }
}

/** The reference a provider issues for a charge or a refund: 1 to 64 characters (data-model section 2). */
@JvmInline
value class ProviderReference(
    val value: String,
) {
    init {
        require(value.length in 1..MAX_LENGTH && value.none(Char::isWhitespace)) {
            "a provider reference has 1 to $MAX_LENGTH characters without spaces"
        }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH: Int = 64
    }
}

/** Decline categories shared by every contract (service conventions section 8); never provider internals. */
enum class DeclineCategory(
    val wire: String,
) {
    INSUFFICIENT_FUNDS("insufficient_funds"),
    CARD_EXPIRED("card_expired"),
    CARD_REJECTED("card_rejected"),
    SUSPECTED_FRAUD("suspected_fraud"),
    INVALID_PAYMENT_METHOD("invalid_payment_method"),
    ;

    companion object {
        /** The category written [wire], if it is one. */
        fun fromWire(wire: String): DeclineCategory? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Outcome of one payment attempt (data-model section 3.5): approved and declined are final, pending means the provider
 * was unreachable and a retry is expected, voided is final too: a pending attempt that will never be resolved because
 * its order was cancelled (or expired), or because a retry superseded it. A voided attempt publishes no event.
 */
enum class PaymentOutcome(
    val wire: String,
) {
    APPROVED("approved"),
    DECLINED("declined"),
    PENDING("pending"),
    VOIDED("voided"),
    ;

    companion object {
        /** The outcome written [wire], if it is one. */
        fun fromWire(wire: String): PaymentOutcome? = entries.firstOrNull { it.wire == wire }
    }
}

/** What an attempt does to the shopper's money. */
enum class AttemptKind(
    val wire: String,
) {
    CHARGE("charge"),
    REFUND("refund"),
}

/** One page of a listing: `page` is 0-based, `size` 1..100 (service conventions section 3). */
@ConsistentCopyVisibility
data class PageRequest private constructor(
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
        ): Either<PaymentError.Invalid, PageRequest> =
            when {
                page < 0 -> PaymentError.Invalid("page", "must be 0 or more").left()
                size !in 1..MAX_SIZE -> PaymentError.Invalid("size", "must be between 1 and $MAX_SIZE").left()
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
