package com.ecommerce.payment.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Duration
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
 * One charge attempt against an order (data-model section 3.5). [outcome] is final once approved, declined or
 * voided; [declineCategory] is present exactly when declined; [providerReference] is absent exactly while pending
 * (the provider was unreachable) or voided (no provider answer will ever be recorded). A pending attempt may be
 * retried: the retry is attempt [attemptNumber] + 1 of the same order, linked by [previousAttemptId], and the attempt
 * it retries is voided. Refunds are separate [RefundRecord]s, so every attempt here has [kind] `charge`.
 */
@Suppress("LongParameterList") // the columns of data-model section 3.5, one by one
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
    val attemptNumber: Int = 1,
    val previousAttemptId: PaymentAttemptId? = null,
) {
    val kind: AttemptKind get() = AttemptKind.CHARGE

    init {
        require(amount.amountMinor > 0) { "a charge is strictly positive" }
        require((declineCategory != null) == (outcome == PaymentOutcome.DECLINED)) {
            "a decline category is present exactly when the attempt is declined"
        }
        require((providerReference == null) == (outcome in UNRESOLVED)) {
            "a provider reference is absent exactly while the attempt is pending or voided"
        }
        require(attemptNumber >= 1) { "attempts are numbered from 1" }
        require((previousAttemptId == null) == (attemptNumber == 1)) {
            "every attempt but the first retries a previous one"
        }
    }

    /** True while the provider's answer is still awaited. */
    val isPending: Boolean get() = outcome == PaymentOutcome.PENDING

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

    /** This pending attempt, voided: it will never be resolved (order cancelled, or superseded by its retry). */
    fun void(): PaymentAttempt {
        check(isPending) { "only a pending attempt can be voided" }
        return copy(outcome = PaymentOutcome.VOIDED)
    }

    /**
     * The retry of this pending attempt as the provider decided it: attempt [attemptNumber] + 1 of the same order,
     * amount and payment method, under a key derived from this attempt's ([IdempotencyKey.retryOf]).
     */
    fun retry(
        id: PaymentAttemptId,
        decision: ProviderDecision,
        at: Instant,
    ): PaymentAttempt {
        check(isPending) { "only a pending attempt is retried" }
        return copy(
            id = id,
            outcome = decision.outcome,
            declineCategory = (decision as? ProviderDecision.Declined)?.category,
            providerReference = decision.reference,
            idempotencyKey = IdempotencyKey.retryOf(idempotencyKey),
            createdAt = at,
            attemptNumber = attemptNumber + 1,
            previousAttemptId = this.id,
        )
    }

    companion object {
        private val UNRESOLVED = setOf(PaymentOutcome.PENDING, PaymentOutcome.VOIDED)

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

/**
 * The bounded retry of pending charges (US4 scenario 7, data-model section 3.5): a pending attempt is retried once it
 * is [delay] old, until the order has [maxAttempts] attempts; the last one stays pending until the order's payment
 * expires, when the order's cancellation voids it.
 */
data class RetryPolicy(
    val delay: Duration,
    val maxAttempts: Int,
) {
    init {
        require(!delay.isNegative) { "the retry delay is not negative" }
        require(maxAttempts >= 1) { "an order has at least one attempt" }
    }

    /** Attempts created at or before this instant are old enough at [now] to be retried. */
    fun cutoff(now: Instant): Instant = now.minus(delay)

    /** True when [attempt] is pending, old enough at [now] and not the last attempt allowed. */
    fun isDue(
        attempt: PaymentAttempt,
        now: Instant,
    ): Boolean = attempt.isPending && attempt.attemptNumber < maxAttempts && !attempt.createdAt.isAfter(cutoff(now))
}
