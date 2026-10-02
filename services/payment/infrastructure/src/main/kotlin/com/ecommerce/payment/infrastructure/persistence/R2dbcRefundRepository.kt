package com.ecommerce.payment.infrastructure.persistence

import com.ecommerce.payment.application.RefundRepository
import com.ecommerce.payment.domain.IdempotencyKey
import com.ecommerce.payment.domain.OrderId
import com.ecommerce.payment.domain.Page
import com.ecommerce.payment.domain.PageRequest
import com.ecommerce.payment.domain.PaymentAttemptId
import com.ecommerce.payment.domain.RefundId
import com.ecommerce.payment.domain.RefundRecord
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import java.time.Instant
import java.util.UUID

/**
 * Refunds in `refunds`, unique per idempotency key and per refunded charge; the insert does nothing on a conflict so
 * that a concurrent refund of the same charge loses cleanly. `announced_at` is set once, conditionally.
 */
class R2dbcRefundRepository(
    private val database: DatabaseClient,
) : RefundRepository {
    override suspend fun insert(refund: RefundRecord): Boolean =
        database
            .sql(INSERT)
            .bind("id", refund.id.value)
            .bind("orderId", refund.orderId.value)
            .bind("accountId", refund.accountId.value)
            .bind("attemptId", refund.attemptId.value)
            .bind("amountMinor", refund.amount.amountMinor)
            .bind("currency", refund.amount.currency)
            .bind("status", refund.status.wire)
            .bind("providerReference", refund.providerReference.value)
            .bind("idempotencyKey", refund.idempotencyKey.value)
            .bind("createdAt", refund.createdAt)
            .bindNullable("announcedAt", refund.announcedAt, Instant::class.java)
            .awaitRowsUpdated() == 1L

    override suspend fun findById(id: RefundId): RefundRecord? = one("id = :value", id.value)

    override suspend fun findByKey(key: IdempotencyKey): RefundRecord? = one("idempotency_key = :value", key.value)

    override suspend fun findByAttempt(attemptId: PaymentAttemptId): RefundRecord? =
        one("attempt_id = :value", attemptId.value)

    override suspend fun findByOrder(
        orderId: OrderId,
        page: PageRequest,
    ): Page<RefundRecord> {
        val items =
            database
                .sql(PAGE_OF_ORDER)
                .bind("orderId", orderId.value)
                .bind("limit", page.size)
                .bind("offset", page.offset)
                .map { row, _ -> row.toRefund() }
                .all()
                .collectList()
                .awaitSingle()
        val total =
            database
                .sql("SELECT count(*) AS total FROM refunds WHERE order_id = :orderId")
                .bind("orderId", orderId.value)
                .map { row, _ -> row.count() }
                .one()
                .awaitSingle()
        return Page(items, page, total)
    }

    override suspend fun markAnnounced(
        id: RefundId,
        at: Instant,
    ): Boolean =
        database
            .sql("UPDATE refunds SET announced_at = :at WHERE id = :id AND announced_at IS NULL")
            .bind("id", id.value)
            .bind("at", at)
            .awaitRowsUpdated() == 1L

    private suspend fun one(
        condition: String,
        value: UUID,
    ): RefundRecord? =
        database
            .sql("$SELECT WHERE $condition")
            .bind("value", value)
            .map { row, _ -> row.toRefund() }
            .awaitOneOrNull()

    private companion object {
        const val ORDER_PAGE_CLAUSE =
            " WHERE order_id = :orderId ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset"
        const val SELECT =
            "SELECT id, order_id, account_id, attempt_id, amount_minor, currency, provider_reference, " +
                "idempotency_key, created_at, announced_at FROM refunds"
        const val PAGE_OF_ORDER = SELECT + ORDER_PAGE_CLAUSE
        const val INSERT =
            "INSERT INTO refunds (id, order_id, account_id, attempt_id, amount_minor, currency, status, " +
                "provider_reference, idempotency_key, created_at, announced_at) VALUES (:id, :orderId, :accountId, " +
                ":attemptId, :amountMinor, :currency, :status, :providerReference, :idempotencyKey, :createdAt, " +
                ":announcedAt) ON CONFLICT DO NOTHING"
    }
}
