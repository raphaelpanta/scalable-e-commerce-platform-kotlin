package com.ecommerce.payment.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant

/** A request to refund an approved charge in full (`RefundRequest` of payment-internal.yaml). */
data class RefundRequest(
    val orderId: OrderId,
    val attemptId: PaymentAttemptId,
    val amount: Money,
    val idempotencyKey: IdempotencyKey,
)

/** The only status of a refund: the simulated provider always accepts refunds. */
enum class RefundStatus(
    val wire: String,
) {
    RECORDED("recorded"),
}

/**
 * The full refund of one approved charge (data-model section 3.5): one per charge, so one per cancellation.
 * [announcedAt] is when `RefundRecorded` was published; it stays null while the owner's contact snapshot, which the
 * event must carry, is unknown (a refund requested over HTTP before the order's `OrderCancelled` arrived).
 */
data class RefundRecord(
    val id: RefundId,
    val orderId: OrderId,
    val accountId: AccountId,
    val attemptId: PaymentAttemptId,
    val amount: Money,
    val providerReference: ProviderReference,
    val idempotencyKey: IdempotencyKey,
    val createdAt: Instant,
    val announcedAt: Instant? = null,
) {
    val status: RefundStatus get() = RefundStatus.RECORDED

    init {
        require(amount.amountMinor > 0) { "a refund is strictly positive" }
    }

    /** True when [request] is the request this refund was created for (an idempotent replay). */
    fun answers(request: RefundRequest): Boolean =
        idempotencyKey == request.idempotencyKey &&
            orderId == request.orderId &&
            attemptId == request.attemptId &&
            amount == request.amount

    /** This refund for a request with the same key, or `IdempotencyKeyReuse` when its body differs. */
    fun replay(request: RefundRequest): Either<PaymentError, RefundRecord> =
        if (answers(request)) right() else PaymentError.IdempotencyKeyReuse.left()

    /** True while `RefundRecorded` has not been published. */
    val awaitingAnnouncement: Boolean get() = announcedAt == null

    companion object {
        /** The refund of [charge] the provider recorded as [reference] for [request]. */
        fun of(
            id: RefundId,
            charge: PaymentAttempt,
            request: RefundRequest,
            reference: ProviderReference,
            at: Instant,
        ): RefundRecord =
            RefundRecord(
                id = id,
                orderId = charge.orderId,
                accountId = charge.accountId,
                attemptId = charge.id,
                amount = charge.amount,
                providerReference = reference,
                idempotencyKey = request.idempotencyKey,
                createdAt = at,
            )
    }
}

/** One refund per charge: a refund under another key for a charge that already has [existing] is refused. */
fun ensureNotRefunded(existing: RefundRecord?): Either<PaymentError, Unit> =
    if (existing == null) Unit.right() else PaymentError.AlreadyRefunded.left()
