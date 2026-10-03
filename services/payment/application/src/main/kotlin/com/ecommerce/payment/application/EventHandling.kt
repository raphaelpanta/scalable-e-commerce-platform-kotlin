package com.ecommerce.payment.application

import com.ecommerce.payment.domain.ChargeRequest
import com.ecommerce.payment.domain.IdempotencyKey
import com.ecommerce.payment.domain.OrderId
import com.ecommerce.payment.domain.PaymentAttempt
import com.ecommerce.payment.domain.PaymentAttemptId
import com.ecommerce.payment.domain.PaymentError
import com.ecommerce.payment.domain.PaymentEvent
import com.ecommerce.payment.domain.Recipient
import com.ecommerce.payment.domain.RefundRecord
import com.ecommerce.payment.domain.RefundRequest
import java.time.Clock

/** What the `OrderPlaced` consumer did. */
sealed interface PlacedOrderCharge {
    /** No attempt existed for the checkout key: the order was charged now. */
    data class Charged(
        val attempt: PaymentAttempt,
    ) : PlacedOrderCharge

    /** The synchronous charge (or an earlier delivery) already recorded the attempt: nothing new. */
    data class Converged(
        val attempt: PaymentAttempt,
    ) : PlacedOrderCharge

    /** The charge was refused (another approved charge, or the key used for another request). */
    data class Refused(
        val error: PaymentError,
    ) : PlacedOrderCharge
}

/**
 * `OrderPlaced` (data-model section 3.5): charges the order under the event's checkout `idempotencyKey`, the key the
 * synchronous `POST /internal/charges` used, so the event converges on that attempt and never creates a second one.
 */
class ChargePlacedOrder(
    private val authoriseCharge: AuthoriseCharge,
) {
    suspend operator fun invoke(request: ChargeRequest): PlacedOrderCharge =
        authoriseCharge(request).fold(
            { PlacedOrderCharge.Refused(it) },
            { if (it.created) PlacedOrderCharge.Charged(it.value) else PlacedOrderCharge.Converged(it.value) },
        )
}

/** The facts of an `OrderCancelled` event the payment context acts on. */
data class CancelledOrder(
    val orderId: OrderId,
    /** True when the payment was approved at cancellation (`paymentStatus` approved, `refundRequired`). */
    val refundRequired: Boolean,
    /** The approved charge named by the event (`paymentId`), when the order knew it. */
    val paymentId: PaymentAttemptId?,
    val recipient: Recipient,
)

/** What the `OrderCancelled` consumer did. */
sealed interface CancellationRefund {
    /** The order has neither an approved charge nor a pending attempt here: nothing to refund or void. */
    data object NotRequired : CancellationRefund

    /** The event says the payment was approved, but the order has no approved charge in this service. */
    data object NoApprovedCharge : CancellationRefund

    /** The order's pending [attempts] were voided (terminal, no event); there is nothing to refund. */
    data class Voided(
        val attempts: List<PaymentAttempt>,
    ) : CancellationRefund

    /** The refund was recorded now and `RefundRecorded` published. */
    data class Refunded(
        val refund: RefundRecord,
    ) : CancellationRefund

    /** A refund recorded earlier over HTTP was announced now that the owner's contact is known. */
    data class Announced(
        val refund: RefundRecord,
    ) : CancellationRefund

    /** The charge was already refunded and announced: nothing new. */
    data class AlreadyRefunded(
        val refund: RefundRecord,
    ) : CancellationRefund

    /** The refund was refused. */
    data class Refused(
        val error: PaymentError,
    ) : CancellationRefund
}

/**
 * `OrderCancelled` (FR-016, data-model section 3.5, conventions section 8), under the order's lock: the order is
 * remembered as cancelled (with the owner's contact snapshot), its pending attempts are voided (terminal, no event,
 * so no retry resolves them later), and its approved charge, if any, gets one full refund under a key derived from the
 * order and one `RefundRecorded`. The refund does not depend on the event's `refundRequired`: an approval that raced
 * the cancellation (the order cancelled while its payment was still pending) is refunded the same way. A refund that
 * already exists (the synchronous `POST /internal/refunds`, a late-approval refund, or a redelivery) is not repeated;
 * if its event still waits for the owner's contact, the event is published now.
 */
class SettleCancelledOrder(
    private val ledger: PaymentLedger,
    private val recordRefund: RecordRefund,
    private val clock: Clock,
) {
    suspend operator fun invoke(order: CancelledOrder): CancellationRefund =
        ledger.transactions.run {
            ledger.cancellations.lock(order.orderId)
            ledger.cancellations.remember(CancelledOrderRecord(order.orderId, order.recipient, clock.instant()))
            val voided = voidPending(order)
            settle(order, approvedChargeOf(order), voided)
        }

    private suspend fun voidPending(order: CancelledOrder): List<PaymentAttempt> =
        ledger.attempts
            .findPendingOf(order.orderId)
            .filter { ledger.attempts.markVoided(it.id) }
            .map { it.void() }

    private suspend fun settle(
        order: CancelledOrder,
        charge: PaymentAttempt?,
        voided: List<PaymentAttempt>,
    ): CancellationRefund {
        val existing = charge?.let { ledger.refunds.findByAttempt(it.id) }
        return when {
            charge == null && order.refundRequired -> CancellationRefund.NoApprovedCharge
            charge == null && voided.isEmpty() -> CancellationRefund.NotRequired
            charge == null -> CancellationRefund.Voided(voided)
            existing == null -> refund(order, charge)
            existing.awaitingAnnouncement -> announce(existing, order.recipient)
            else -> CancellationRefund.AlreadyRefunded(existing)
        }
    }

    private suspend fun approvedChargeOf(order: CancelledOrder): PaymentAttempt? =
        order.paymentId
            ?.let { ledger.attempts.findById(it) }
            ?.takeIf { it.isApprovedChargeOf(order.orderId) }
            ?: ledger.attempts.findApprovedCharge(order.orderId)

    private suspend fun refund(
        order: CancelledOrder,
        charge: PaymentAttempt,
    ): CancellationRefund =
        recordRefund(
            RefundRequest(order.orderId, charge.id, charge.amount, IdempotencyKey.refundOf(order.orderId)),
            order.recipient,
        ).fold({ CancellationRefund.Refused(it) }, { CancellationRefund.Refunded(it.value) })

    private suspend fun announce(
        refund: RefundRecord,
        recipient: Recipient,
    ): CancellationRefund {
        val now = clock.instant()
        return if (ledger.refunds.markAnnounced(refund.id, now)) {
            val announced = refund.copy(announcedAt = now)
            ledger.events.publish(PaymentEvent.RefundRecorded(announced, recipient))
            CancellationRefund.Announced(announced)
        } else {
            CancellationRefund.AlreadyRefunded(refund)
        }
    }
}
