package com.ecommerce.payment.infrastructure.persistence

import com.ecommerce.payment.application.CancelledOrderRecord
import com.ecommerce.payment.application.CancelledOrderRepository
import com.ecommerce.payment.domain.OrderId
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull

/**
 * Cancelled orders in `cancelled_orders` (inserted once, never updated), and the per-order lock: a PostgreSQL
 * transaction-level advisory lock keyed by the order id, released when the running transaction ends. Charges and
 * cancellations of the same order take it before they read each other's rows, so neither misses the other.
 */
class R2dbcCancelledOrderRepository(
    private val database: DatabaseClient,
) : CancelledOrderRepository {
    override suspend fun lock(orderId: OrderId) {
        database
            .sql("SELECT pg_advisory_xact_lock(:key)")
            .bind("key", lockKey(orderId))
            .then()
            .awaitSingleOrNull()
    }

    override suspend fun remember(order: CancelledOrderRecord): Boolean =
        database
            .sql(INSERT)
            .bind("orderId", order.orderId.value)
            .bind("accountId", order.recipient.accountId.value)
            .bind("email", order.recipient.email)
            .bindNullable("phone", order.recipient.phone, String::class.java)
            .bind("channels", order.recipient.preferredChannels.toTypedArray())
            .bind("recordedAt", order.recordedAt)
            .awaitRowsUpdated() == 1L

    override suspend fun find(orderId: OrderId): CancelledOrderRecord? =
        database
            .sql(
                "SELECT order_id, account_id, email, phone, preferred_channels, recorded_at FROM cancelled_orders " +
                    "WHERE order_id = :orderId",
            ).bind("orderId", orderId.value)
            .map { row, _ -> row.toCancelledOrder() }
            .awaitOneOrNull()

    private companion object {
        /** Namespace of the payment context's advisory locks, folded into the order id's 64-bit key. */
        const val LOCK_NAMESPACE = 0x7061796d656e74L // "payment"

        const val INSERT =
            "INSERT INTO cancelled_orders (order_id, account_id, email, phone, preferred_channels, recorded_at) " +
                "VALUES (:orderId, :accountId, :email, :phone, :channels, :recordedAt) ON CONFLICT DO NOTHING"

        fun lockKey(orderId: OrderId): Long =
            orderId.value.mostSignificantBits xor orderId.value.leastSignificantBits xor LOCK_NAMESPACE
    }
}
