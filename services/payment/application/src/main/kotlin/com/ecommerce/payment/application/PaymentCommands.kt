package com.ecommerce.payment.application

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import arrow.core.right
import com.ecommerce.payment.domain.ChargeRequest
import com.ecommerce.payment.domain.PaymentAttempt
import com.ecommerce.payment.domain.PaymentError
import com.ecommerce.payment.domain.PaymentEvent
import com.ecommerce.payment.domain.PaymentProviderPort
import com.ecommerce.payment.domain.Recipient
import com.ecommerce.payment.domain.RefundRecord
import com.ecommerce.payment.domain.RefundRequest
import com.ecommerce.payment.domain.ensureNotCharged
import com.ecommerce.payment.domain.ensureNotRefunded
import java.time.Clock

/**
 * The stores of the payment context and the outbox their events go to: every change stores the record and publishes
 * its event in one [transactions] block (no dual write).
 */
class PaymentLedger(
    val attempts: PaymentAttemptRepository,
    val refunds: RefundRepository,
    val events: PaymentEventPublisher,
    val transactions: Transactions,
)

/** A stored record and whether this request created it (201) or replayed it (200). */
data class Recorded<out T>(
    val value: T,
    val created: Boolean,
)

/**
 * `POST /internal/charges` and the `OrderPlaced` consumer (FR-013, FR-014): idempotent on the `Idempotency-Key`.
 * The same key with the same body returns the stored attempt unchanged (no new provider call); the same key with a
 * different body is `IdempotencyKeyReuse`; a new key for an order that already has an approved charge is
 * `AlreadyCharged`. Otherwise the provider decides, and the attempt is stored with its `PaymentApproved`,
 * `PaymentDeclined` or `PaymentPending` event in one transaction. A concurrent request that stored first wins.
 */
class AuthoriseCharge(
    private val ledger: PaymentLedger,
    private val provider: PaymentProviderPort,
    private val ids: PaymentIds,
    private val clock: Clock,
) {
    private val attempts = ledger.attempts

    suspend operator fun invoke(request: ChargeRequest): Either<PaymentError, Recorded<PaymentAttempt>> =
        either {
            val existing = attempts.findByKey(request.idempotencyKey)
            if (existing != null) {
                Recorded(existing.replay(request).bind(), created = false)
            } else {
                ensureNotCharged(attempts.findApprovedCharge(request.orderId)).bind()
                val decision = provider.charge(request.paymentMethodRef, request.amount)
                val attempt = PaymentAttempt.charge(ids.nextAttempt(), request, decision, clock.instant())
                ledger.transactions.run { store(attempt, request) }.bind()
            }
        }

    private suspend fun store(
        attempt: PaymentAttempt,
        request: ChargeRequest,
    ): Either<PaymentError, Recorded<PaymentAttempt>> =
        if (attempts.insert(attempt)) {
            ledger.events.publish(PaymentEvent.ChargeRecorded(attempt))
            Recorded(attempt, created = true).right()
        } else {
            attempts.findByKey(request.idempotencyKey)?.replay(request)?.map { Recorded(it, created = false) }
                ?: PaymentError.AlreadyCharged.left()
        }
}

/**
 * `POST /internal/refunds` and the `OrderCancelled` consumer (FR-016): a full refund of an approved charge,
 * idempotent on the key like [AuthoriseCharge]. An unknown attempt, or one of another order, is `AttemptNotFound`;
 * a charge that is not approved is `NotRefundable`; an amount other than the charged one is `Invalid`; a second
 * refund of the charge under a new key is `AlreadyRefunded`. The simulated provider always accepts the refund.
 * `RefundRecorded` is published with the refund when the owner's contact snapshot ([Recipient]) is known; otherwise
 * the refund waits for the `OrderCancelled` event, which carries it ([RefundCancelledOrder]).
 */
class RecordRefund(
    private val ledger: PaymentLedger,
    private val provider: PaymentProviderPort,
    private val ids: PaymentIds,
    private val clock: Clock,
) {
    private val refunds = ledger.refunds

    suspend operator fun invoke(
        request: RefundRequest,
        recipient: Recipient? = null,
    ): Either<PaymentError, Recorded<RefundRecord>> =
        either {
            val existing = refunds.findByKey(request.idempotencyKey)
            if (existing != null) {
                Recorded(existing.replay(request).bind(), created = false)
            } else {
                val charge = ensureNotNull(ledger.attempts.findById(request.attemptId)) { PaymentError.AttemptNotFound }
                charge.refundable(request).bind()
                ensureNotRefunded(refunds.findByAttempt(charge.id)).bind()
                val reference = provider.refund(checkNotNull(charge.providerReference), charge.amount)
                val now = clock.instant()
                val refund =
                    RefundRecord
                        .of(ids.nextRefund(), charge, request, reference, now)
                        .copy(announcedAt = recipient?.let { now })
                ledger.transactions.run { store(refund, request, recipient) }.bind()
            }
        }

    private suspend fun store(
        refund: RefundRecord,
        request: RefundRequest,
        recipient: Recipient?,
    ): Either<PaymentError, Recorded<RefundRecord>> =
        if (refunds.insert(refund)) {
            recipient?.let { ledger.events.publish(PaymentEvent.RefundRecorded(refund, it)) }
            Recorded(refund, created = true).right()
        } else {
            refunds.findByKey(request.idempotencyKey)?.replay(request)?.map { Recorded(it, created = false) }
                ?: PaymentError.AlreadyRefunded.left()
        }
}
