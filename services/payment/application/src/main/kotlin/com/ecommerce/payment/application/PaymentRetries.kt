package com.ecommerce.payment.application

import com.ecommerce.payment.domain.PaymentAttempt
import com.ecommerce.payment.domain.PaymentProviderPort
import com.ecommerce.payment.domain.RetryPolicy
import java.time.Clock

/** What one retry of a pending attempt did. */
sealed interface PendingRetry {
    /** The attempt was voided and its retry stored (and published, unless the order was cancelled meanwhile). */
    data class Retried(
        val voided: PaymentAttempt,
        val retry: PaymentAttempt,
    ) : PendingRetry

    /** The order already has an approved charge (under another key): the stale attempt was voided, not retried. */
    data class Superseded(
        val voided: PaymentAttempt,
    ) : PendingRetry

    /** The attempt was no longer pending (voided by a cancellation, or retried by another instance): nothing done. */
    data object Settled : PendingRetry
}

/**
 * The bounded retry of pending charges (US4 scenario 7, data-model section 3.5), run by a scheduled job: every pending
 * attempt that the [policy] says is due (old enough, not the last attempt allowed) is charged again through the
 * provider as attempt N + 1. Under the order's lock, the pending attempt is voided and the retry stored with its
 * `PaymentApproved`, `PaymentDeclined` or `PaymentPending` event ([ChargeSettlement]), in one transaction; the order
 * service applies the outcome while its payment is still pending. An attempt voided meanwhile (the order was cancelled
 * or expired) is left alone, so retries stop with the order. Several instances are safe: the conditional void lets
 * one of them retry an attempt.
 */
class RetryPendingCharges(
    private val ledger: PaymentLedger,
    private val provider: PaymentProviderPort,
    private val ids: PaymentIds,
    private val clock: Clock,
    private val policy: RetryPolicy,
    private val settlement: ChargeSettlement,
) {
    /** Retries up to [batchSize] due attempts; returns how many were due (a full batch asks for another pass). */
    suspend operator fun invoke(batchSize: Int): Int {
        val due = ledger.attempts.findDueForRetry(policy.cutoff(clock.instant()), policy.maxAttempts, batchSize)
        due.forEach { retry(it) }
        return due.size
    }

    /** Retries [pending], one attempt of the batch. */
    suspend fun retry(pending: PaymentAttempt): PendingRetry =
        if (ledger.attempts.findApprovedCharge(pending.orderId) != null) {
            ledger.transactions.run {
                ledger.cancellations.lock(pending.orderId)
                val voided = ledger.attempts.markVoided(pending.id)
                if (voided) PendingRetry.Superseded(pending.void()) else PendingRetry.Settled
            }
        } else {
            val decision = provider.charge(pending.paymentMethodRef, pending.amount, pending.attemptNumber + 1)
            val next = pending.retry(ids.nextAttempt(), decision, clock.instant())
            ledger.transactions.run {
                ledger.cancellations.lock(pending.orderId)
                if (ledger.attempts.markVoided(pending.id)) {
                    val stored = checkNotNull(settlement.store(next)) { "the retry of ${pending.id} was not stored" }
                    PendingRetry.Retried(pending.void(), stored)
                } else {
                    PendingRetry.Settled
                }
            }
        }
}
