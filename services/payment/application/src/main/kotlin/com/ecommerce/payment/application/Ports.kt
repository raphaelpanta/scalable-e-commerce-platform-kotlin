package com.ecommerce.payment.application

import com.ecommerce.payment.domain.AccountId
import com.ecommerce.payment.domain.IdempotencyKey
import com.ecommerce.payment.domain.OrderId
import com.ecommerce.payment.domain.Page
import com.ecommerce.payment.domain.PageRequest
import com.ecommerce.payment.domain.PaymentAttempt
import com.ecommerce.payment.domain.PaymentAttemptId
import com.ecommerce.payment.domain.PaymentEvent
import com.ecommerce.payment.domain.Recipient
import com.ecommerce.payment.domain.RefundId
import com.ecommerce.payment.domain.RefundRecord
import java.time.Instant

/**
 * Persistence of charge attempts: unique per idempotency key, at most one approved charge per order, at most one
 * retry per attempt.
 */
interface PaymentAttemptRepository {
    /**
     * Stores a new attempt; false (and nothing stored) when an attempt with the same idempotency key, a second
     * approved charge of the same order, or a second retry of the same attempt already exists.
     */
    suspend fun insert(attempt: PaymentAttempt): Boolean

    suspend fun findById(id: PaymentAttemptId): PaymentAttempt?

    suspend fun findByKey(key: IdempotencyKey): PaymentAttempt?

    /** The approved charge of [orderId], if any. */
    suspend fun findApprovedCharge(orderId: OrderId): PaymentAttempt?

    /** The account that owns [orderId] according to its attempts, or null when the order has none. */
    suspend fun ownerOf(orderId: OrderId): AccountId?

    /** The attempts of [orderId], newest first. */
    suspend fun findByOrder(
        orderId: OrderId,
        page: PageRequest,
    ): Page<PaymentAttempt>

    /** The pending attempts of [orderId]. */
    suspend fun findPendingOf(orderId: OrderId): List<PaymentAttempt>

    /**
     * Up to [limit] pending attempts created at or before [createdUpTo] whose attempt number is below [maxAttempts],
     * oldest first: the attempts the retry job is due to retry.
     */
    suspend fun findDueForRetry(
        createdUpTo: Instant,
        maxAttempts: Int,
        limit: Int,
    ): List<PaymentAttempt>

    /** Voids the attempt [id] if it is still pending; false (nothing changed) when it is not pending. */
    suspend fun markVoided(id: PaymentAttemptId): Boolean
}

/**
 * What the payment context remembers of a cancelled order (data-model section 3.5): that it is cancelled, and the
 * owner's contact snapshot of its `OrderCancelled` event, which the `RefundRecorded` of a late approval must carry.
 * Personal data: its [toString] reveals nothing but the order.
 */
data class CancelledOrderRecord(
    val orderId: OrderId,
    val recipient: Recipient,
    val recordedAt: Instant,
) {
    override fun toString(): String = "CancelledOrderRecord(orderId=$orderId, recordedAt=$recordedAt)"
}

/** Persistence of the cancelled orders, and the per-order lock that orders charges against cancellations. */
interface CancelledOrderRepository {
    /**
     * Serialises, until the running transaction ends, every transaction that settles a charge of [orderId] or applies
     * its cancellation, so that a charge and a cancellation always see each other's outcome. Must run inside
     * [Transactions.run].
     */
    suspend fun lock(orderId: OrderId)

    /** Remembers [order] as cancelled; false (nothing changed) when it already was. */
    suspend fun remember(order: CancelledOrderRecord): Boolean

    suspend fun find(orderId: OrderId): CancelledOrderRecord?
}

/** Persistence of refunds: unique per idempotency key and per refunded charge. */
interface RefundRepository {
    /** Stores a new refund; false (and nothing stored) when its key or its charge already has a refund. */
    suspend fun insert(refund: RefundRecord): Boolean

    suspend fun findById(id: RefundId): RefundRecord?

    suspend fun findByKey(key: IdempotencyKey): RefundRecord?

    suspend fun findByAttempt(attemptId: PaymentAttemptId): RefundRecord?

    /** The refunds of [orderId], newest first. */
    suspend fun findByOrder(
        orderId: OrderId,
        page: PageRequest,
    ): Page<RefundRecord>

    /** Records that `RefundRecorded` was published for [id] at [at]; false when it already was. */
    suspend fun markAnnounced(
        id: RefundId,
        at: Instant,
    ): Boolean
}

/** Publishes payment events through the transactional outbox; must run inside [Transactions.run]. */
interface PaymentEventPublisher {
    suspend fun publish(event: PaymentEvent)
}

/** One database transaction around [block]; a nested call joins the running one (an event consumer's). */
interface Transactions {
    suspend fun <T> run(block: suspend () -> T): T
}

/** New identifiers. */
interface PaymentIds {
    fun nextAttempt(): PaymentAttemptId

    fun nextRefund(): RefundId
}
