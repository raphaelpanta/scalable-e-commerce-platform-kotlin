package com.ecommerce.order.infrastructure.persistence

import com.ecommerce.order.application.OrderRepository
import com.ecommerce.order.domain.AccountId
import com.ecommerce.order.domain.Order
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.OrderNumber
import com.ecommerce.order.domain.OrderStatus
import com.ecommerce.order.domain.Page
import com.ecommerce.order.domain.PageRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOne
import org.springframework.r2dbc.core.flow
import java.time.Instant
import java.time.LocalDate

/**
 * [OrderRepository] on the service's PostgreSQL over R2DBC: the order row, its lines and its append-only status
 * history. Writes join the caller's reactive transaction; updates compare the version (optimistic locking).
 */
class R2dbcOrderRepository(
    private val database: DatabaseClient,
) : OrderRepository {
    override suspend fun nextOrderNumber(day: LocalDate): OrderNumber {
        val sequence =
            database
                .sql(NEXT_NUMBER)
                .bind("day", day)
                .map { row, _ -> checkNotNull(row.get("last_value", Long::class.javaObjectType)) }
                .awaitOne()
        return OrderNumber.of(day, sequence)
    }

    override suspend fun insert(order: Order) {
        OrderRows
            .bindOrder(database.sql(INSERT_ORDER), order)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        order.lines.forEachIndexed { position, line ->
            database
                .sql(INSERT_LINE)
                .bind("orderId", order.id.value)
                .bind("position", position)
                .bind("productId", line.productId.value)
                .bind("sku", line.sku)
                .bind("name", line.name)
                .bind("unitPrice", line.unitPrice.amountMinor)
                .bind("currency", line.unitPrice.currency)
                .bind("quantity", line.quantity.value)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        appendHistory(order)
    }

    override suspend fun update(order: Order): Boolean {
        val updated =
            OrderRows
                .bindMutable(database.sql(UPDATE_ORDER), order)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        if (updated == 1L) appendHistory(order)
        return updated == 1L
    }

    override suspend fun findById(id: OrderId): Order? =
        load("$SELECT_ORDER WHERE id = :id") {
            bind("id", id.value)
        }.firstOrNull()

    override suspend fun search(
        accountId: AccountId?,
        status: OrderStatus?,
        page: PageRequest,
    ): Page<Order> {
        val conditions =
            listOfNotNull(
                "account_id = :accountId".takeIf { accountId != null },
                "order_status = :orderStatus".takeIf { status != null },
            )
        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        fun DatabaseClient.GenericExecuteSpec.filtered(): DatabaseClient.GenericExecuteSpec {
            val withAccount = if (accountId == null) this else bind("accountId", accountId.value)
            return if (status == null) withAccount else withAccount.bind("orderStatus", status.wire)
        }
        val orders =
            load("$SELECT_ORDER$where ORDER BY placed_at DESC, id DESC LIMIT :limit OFFSET :offset") {
                filtered().bind("limit", page.size).bind("offset", page.offset)
            }
        val total =
            database
                .sql("SELECT count(*) AS total FROM orders$where")
                .filtered()
                .map { row, _ -> checkNotNull(row.get("total", Long::class.javaObjectType)) }
                .awaitOne()
        return Page(orders, page, total)
    }

    override suspend fun findAllByAccount(accountId: AccountId): List<Order> =
        load("$SELECT_ORDER WHERE account_id = :accountId ORDER BY placed_at") { bind("accountId", accountId.value) }

    override suspend fun findExpiredPendingPayments(
        now: Instant,
        limit: Int,
    ): List<Order> =
        load(
            "$SELECT_ORDER WHERE payment_status = 'pending' AND payment_expires_at <= :now " +
                "ORDER BY payment_expires_at LIMIT :limit",
        ) { bind("now", now).bind("limit", limit) }

    private suspend fun appendHistory(order: Order) {
        order.history.forEachIndexed { position, change ->
            OrderRows
                .bindChange(database.sql(INSERT_CHANGE), order.id.value, position, change)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    private suspend fun load(
        sql: String,
        bind: DatabaseClient.GenericExecuteSpec.() -> DatabaseClient.GenericExecuteSpec,
    ): List<Order> {
        val heads =
            database
                .sql(sql)
                .bind()
                .map { row, _ -> OrderRows.head(row) }
                .flow()
                .toList()
        if (heads.isEmpty()) return emptyList()
        val ids = heads.map { it.id }.toTypedArray()
        val lines =
            database
                .sql("SELECT * FROM order_lines WHERE order_id = ANY(:ids) ORDER BY order_id, position")
                .bind("ids", ids)
                .map { row, _ -> OrderRows.lineOf(row) }
                .flow()
                .toList()
                .groupBy({ it.first }, { it.second })
        val history =
            database
                .sql("SELECT * FROM status_changes WHERE order_id = ANY(:ids) ORDER BY order_id, position")
                .bind("ids", ids)
                .map { row, _ -> OrderRows.changeOf(row) }
                .flow()
                .toList()
                .groupBy({ it.first }, { it.second })
        return heads.map { it.toOrder(lines[it.id].orEmpty(), history[it.id].orEmpty()) }
    }

    private companion object {
        const val SELECT_ORDER = "SELECT * FROM orders"
        const val NEXT_NUMBER =
            "INSERT INTO order_number_sequences (day, last_value) VALUES (:day, 1) " +
                "ON CONFLICT (day) DO UPDATE SET last_value = order_number_sequences.last_value + 1 " +
                "RETURNING last_value"
        const val INSERT_ORDER =
            "INSERT INTO orders (id, order_number, account_id, account_pseudonym, order_status, payment_status, " +
                "cancellation_reason, cancelled_at, cancelled_by, decline_category, total_minor, currency, " +
                "recipient_name, address_line1, address_line2, city, region, postal_code, country, recipient_email, " +
                "recipient_phone, recipient_channels, payment_method_ref, idempotency_key, reservation_id, " +
                "payment_attempt_id, paid_at, payment_expires_at, refund_id, refund_recorded_at, placed_at, version) " +
                "VALUES (:id, :orderNumber, :accountId, :accountPseudonym, :orderStatus, :paymentStatus, " +
                ":cancellationReason, :cancelledAt, :cancelledBy, :declineCategory, :totalMinor, :currency, " +
                ":recipientName, :line1, :line2, :city, :region, :postalCode, :country, :recipientEmail, " +
                ":recipientPhone, :recipientChannels, :paymentMethodRef, :idempotencyKey, :reservationId, " +
                ":paymentAttemptId, :paidAt, :paymentExpiresAt, :refundId, :refundRecordedAt, :placedAt, :version)"
        const val UPDATE_ORDER =
            "UPDATE orders SET account_pseudonym = :accountPseudonym, order_status = :orderStatus, " +
                "payment_status = :paymentStatus, cancellation_reason = :cancellationReason, " +
                "cancelled_at = :cancelledAt, cancelled_by = :cancelledBy, decline_category = :declineCategory, " +
                "recipient_name = :recipientName, address_line1 = :line1, address_line2 = :line2, city = :city, " +
                "region = :region, postal_code = :postalCode, country = :country, " +
                "recipient_email = :recipientEmail, recipient_phone = :recipientPhone, " +
                "recipient_channels = :recipientChannels, payment_attempt_id = :paymentAttemptId, " +
                "paid_at = :paidAt, payment_expires_at = :paymentExpiresAt, refund_id = :refundId, " +
                "refund_recorded_at = :refundRecordedAt, version = :version + 1 " +
                "WHERE id = :id AND version = :version"
        const val INSERT_LINE =
            "INSERT INTO order_lines (order_id, position, product_id, sku, name, unit_price_minor, currency, " +
                "quantity) VALUES (:orderId, :position, :productId, :sku, :name, :unitPrice, :currency, :quantity)"
        const val INSERT_CHANGE =
            "INSERT INTO status_changes (order_id, position, kind, from_status, to_status, changed_at, changed_by, " +
                "reason) VALUES (:orderId, :position, :kind, :fromStatus, :toStatus, :changedAt, :changedBy, " +
                ":reason) ON CONFLICT (order_id, position) DO NOTHING"
    }
}
