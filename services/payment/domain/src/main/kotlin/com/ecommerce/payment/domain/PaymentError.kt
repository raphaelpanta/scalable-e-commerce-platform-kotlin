package com.ecommerce.payment.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/** Expected failures of the payment context, returned as values and answered as RFC 9457 problems. */
sealed interface PaymentError {
    /** A semantically invalid input (422 `validation`). */
    data class Invalid(
        val field: String,
        val reason: String,
    ) : PaymentError

    /** The `Idempotency-Key` was already used with a different request body (422 `validation`). */
    data object IdempotencyKeyReuse : PaymentError

    /** The order already has an approved charge; a new key cannot charge it again (409 `conflict`). */
    data object AlreadyCharged : PaymentError

    /** The attempt is unknown, or belongs to another order than the one named (404 `not-found`). */
    data object AttemptNotFound : PaymentError

    /** Only an approved charge can be refunded (409 `conflict`). */
    data object NotRefundable : PaymentError

    /** The charge already has its refund, recorded under another key (409 `conflict`). */
    data object AlreadyRefunded : PaymentError

    /** The payment or refund does not exist or is not visible to the caller (404 `not-found`). */
    data object NotFound : PaymentError

    /** The caller's role does not allow the operation (403 `forbidden`). */
    data object Forbidden : PaymentError
}

/** Roles of an access token that the payment context distinguishes. */
enum class Role {
    SHOPPER,
    OPERATOR,
}

/** The authenticated account behind a read request. */
data class Caller(
    val accountId: AccountId,
    val roles: Set<Role>,
) {
    val isOperator: Boolean get() = Role.OPERATOR in roles

    /** Operators see every payment; a shopper only the payments of their own orders (others are answered 404). */
    fun canSee(owner: AccountId): Boolean = isOperator || (Role.SHOPPER in roles && owner == accountId)

    /**
     * Whether the caller may list the payments of an order owned by [owner]: always when the order has no payments
     * yet (`null`, an empty page), otherwise when the caller [canSee] the owner; another shopper's order is 404.
     */
    fun canList(owner: AccountId?): Either<PaymentError, Unit> =
        if (owner == null || canSee(owner)) Unit.right() else PaymentError.NotFound.left()
}

/**
 * The contact snapshot of the order owner that the `RefundRecorded` event carries for the notification service
 * (events.yaml `RecipientSnapshot`). Personal data: its [toString] reveals nothing but the account.
 */
data class Recipient(
    val accountId: AccountId,
    val email: String,
    val phone: String?,
    val preferredChannels: List<String>,
) {
    override fun toString(): String = "Recipient(accountId=$accountId, contact=***)"
}
