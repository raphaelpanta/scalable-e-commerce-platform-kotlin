package com.ecommerce.order.domain

import java.time.Instant

/**
 * The outcome of one charge attempt as the order context sees it, from the synchronous charge call or from the
 * payment events (data-model section 3.5); both paths go through [applyPayment] and converge.
 */
sealed interface PaymentOutcome {
    /** The provider approved the charge. */
    data class Approved(
        val attemptId: PaymentAttemptId,
    ) : PaymentOutcome

    /** The provider declined the charge with [category]. */
    data class Declined(
        val attemptId: PaymentAttemptId?,
        val category: DeclineCategory,
    ) : PaymentOutcome

    /** The provider (or the payment service) could not be reached; [attemptId] is known when an attempt exists. */
    data class Pending(
        val attemptId: PaymentAttemptId?,
    ) : PaymentOutcome
}

/**
 * Applies a payment outcome while the payment is `pending`: approved -> `approved` and `OrderPaid`; declined ->
 * `failed`, cancelled `PAYMENT_FAILED` and `OrderPaymentFailed`; pending -> only the attempt id is noted. Once the
 * payment is resolved (or voided by a cancellation) any outcome changes nothing, so a duplicate or late outcome is
 * never applied twice.
 */
fun Order.applyPayment(
    outcome: PaymentOutcome,
    at: Instant,
): OrderChange =
    if (paymentStatus != PaymentStatus.PENDING) {
        unchanged()
    } else {
        when (outcome) {
            is PaymentOutcome.Approved -> approve(outcome.attemptId, at)
            is PaymentOutcome.Declined -> decline(outcome, at)
            is PaymentOutcome.Pending -> notePending(outcome.attemptId)
        }
    }

private fun Order.approve(
    attemptId: PaymentAttemptId,
    at: Instant,
): OrderChange {
    val paid =
        copy(
            paymentStatus = PaymentStatus.APPROVED,
            paymentAttemptId = attemptId,
            paidAt = at,
            paymentExpiresAt = null,
            history = history + StatusChange.payment(PaymentStatus.PENDING, PaymentStatus.APPROVED, at),
        )
    return OrderChange(paid, listOf(OrderEvent.Paid(paid, at)))
}

private fun Order.decline(
    outcome: PaymentOutcome.Declined,
    at: Instant,
): OrderChange {
    val failed =
        copy(
            orderStatus = OrderStatus.CANCELLED,
            paymentStatus = PaymentStatus.FAILED,
            paymentAttemptId = outcome.attemptId ?: paymentAttemptId,
            declineCategory = outcome.category,
            paymentExpiresAt = null,
            cancellation = Cancellation(CancellationReason.PAYMENT_FAILED, at, Actor.System.by),
            history =
                history +
                    StatusChange.payment(PaymentStatus.PENDING, PaymentStatus.FAILED, at) +
                    StatusChange.order(
                        orderStatus,
                        OrderStatus.CANCELLED,
                        at,
                        Actor.System,
                        CancellationReason.PAYMENT_FAILED,
                    ),
        ).scrubIfAnonymised()
    return OrderChange(failed, listOf(OrderEvent.PaymentFailed(failed, at)))
}

private fun Order.notePending(attemptId: PaymentAttemptId?): OrderChange =
    if (attemptId != null && paymentAttemptId == null) {
        OrderChange(copy(paymentAttemptId = attemptId), emptyList())
    } else {
        unchanged()
    }

/** True when the payment is still `pending` at [now], once its payment window (from placement) has ended. */
fun Order.paymentExpired(now: Instant): Boolean =
    paymentStatus == PaymentStatus.PENDING && paymentExpiresAt != null && !now.isBefore(paymentExpiresAt)

/**
 * Expiry job (FR-015): a payment still `pending` after its window is voided (`failed`) and the order cancelled with
 * `PAYMENT_EXPIRED` and `OrderCancelled`; any other order is left unchanged.
 */
fun Order.expirePayment(now: Instant): OrderChange =
    if (paymentExpired(now)) cancel(CancellationReason.PAYMENT_EXPIRED, Actor.System, now) else unchanged()

/** `RefundRecorded`: records the refund once; later deliveries change nothing. */
fun Order.recordRefund(
    refundId: RefundId,
    at: Instant,
): OrderChange = if (refund == null) OrderChange(copy(refund = Refund(refundId, at)), emptyList()) else unchanged()
