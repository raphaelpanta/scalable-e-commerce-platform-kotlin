package com.ecommerce.payment.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant

/** A request to charge an order (`ChargeRequest` of payment-internal.yaml, or an `OrderPlaced` event). */
data class ChargeRequest(
    val orderId: OrderId,
    val accountId: AccountId,
    val amount: Money,
    val paymentMethodRef: PaymentMethodRef,
    val idempotencyKey: IdempotencyKey,
) {
    init {
        require(amount.amountMinor > 0) { "a charge is strictly positive" }
    }
}

/**
 * One charge attempt against an order (data-model section 3.5). [outcome] is final once approved or declined;
 * [declineCategory] is present exactly when declined; [providerReference] is absent exactly while pending (the
 * provider was unreachable). Refunds are separate [RefundRecord]s, so every attempt here has [kind] `charge`.
 */
data class PaymentAttempt(
    val id: PaymentAttemptId,
    val orderId: OrderId,
    val accountId: AccountId,
    val amount: Money,
    val paymentMethodRef: PaymentMethodRef,
    val outcome: PaymentOutcome,
    val declineCategory: DeclineCategory?,
    val providerReference: ProviderReference?,
    val idempotencyKey: IdempotencyKey,
    val createdAt: Instant,
) {
    val kind: AttemptKind get() = AttemptKind.CHARGE

    init {
        require(amount.amountMinor > 0) { "a charge is strictly positive" }
        require((declineCategory != null) == (outcome == PaymentOutcome.DECLINED)) {
            "a decline category is present exactly when the attempt is declined"
        }
        require((providerReference == null) == (outcome == PaymentOutcome.PENDING)) {
            "a provider reference is absent exactly while the attempt is pending"
        }
    }

    /** True when [request] is the request this attempt was created for (an idempotent replay). */
    fun answers(request: ChargeRequest): Boolean =
        idempotencyKey == request.idempotencyKey &&
            orderId == request.orderId &&
            accountId == request.accountId &&
            amount == request.amount &&
            paymentMethodRef == request.paymentMethodRef

    /** True when this attempt is the approved charge of [order]. */
    fun isApprovedChargeOf(order: OrderId): Boolean = orderId == order && outcome == PaymentOutcome.APPROVED

    /**
     * The stored attempt as the answer to a request with the same key: the attempt itself when [request] is the same
     * request, `IdempotencyKeyReuse` when the key comes with a different body.
     */
    fun replay(request: ChargeRequest): Either<PaymentError, PaymentAttempt> =
        if (answers(request)) right() else PaymentError.IdempotencyKeyReuse.left()

    /**
     * Whether [request] may refund this attempt: it must name this attempt's order, the attempt must be an approved
     * charge and the amount must be the full charged amount (MVP: full refunds only).
     */
    fun refundable(request: RefundRequest): Either<PaymentError, PaymentAttempt> =
        when {
            request.attemptId != id || request.orderId != orderId -> PaymentError.AttemptNotFound.left()
            outcome != PaymentOutcome.APPROVED -> PaymentError.NotRefundable.left()
            request.amount != amount -> PaymentError.Invalid("amount", "must equal the charged amount").left()
            else -> right()
        }

    companion object {
        /** The attempt recording the provider's [decision] for [request]. */
        fun charge(
            id: PaymentAttemptId,
            request: ChargeRequest,
            decision: ProviderDecision,
            at: Instant,
        ): PaymentAttempt =
            PaymentAttempt(
                id = id,
                orderId = request.orderId,
                accountId = request.accountId,
                amount = request.amount,
                paymentMethodRef = request.paymentMethodRef,
                outcome = decision.outcome,
                declineCategory = (decision as? ProviderDecision.Declined)?.category,
                providerReference = decision.reference,
                idempotencyKey = request.idempotencyKey,
                createdAt = at,
            )
    }
}

/**
 * At most one approved charge per order (FR-013): a request under a new key for an order whose [approved] charge
 * already exists is refused with `AlreadyCharged`; without one the charge may go ahead.
 */
fun ensureNotCharged(approved: PaymentAttempt?): Either<PaymentError, Unit> =
    if (approved == null) Unit.right() else PaymentError.AlreadyCharged.left()
