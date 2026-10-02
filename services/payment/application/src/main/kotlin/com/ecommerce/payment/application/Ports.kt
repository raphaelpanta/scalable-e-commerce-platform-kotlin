package com.ecommerce.payment.application

import com.ecommerce.payment.domain.AccountId
import com.ecommerce.payment.domain.IdempotencyKey
import com.ecommerce.payment.domain.OrderId
import com.ecommerce.payment.domain.Page
import com.ecommerce.payment.domain.PageRequest
import com.ecommerce.payment.domain.PaymentAttempt
import com.ecommerce.payment.domain.PaymentAttemptId
import com.ecommerce.payment.domain.PaymentEvent
import com.ecommerce.payment.domain.RefundId
import com.ecommerce.payment.domain.RefundRecord
import java.time.Instant

/** Persistence of charge attempts: unique per idempotency key, at most one approved charge per order. */
interface PaymentAttemptRepository {
    /**
     * Stores a new attempt; false (and nothing stored) when an attempt with the same idempotency key, or a second
     * approved charge of the same order, already exists.
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
