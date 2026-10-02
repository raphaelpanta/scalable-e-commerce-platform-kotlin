package com.ecommerce.catalog.infrastructure.persistence

import com.ecommerce.catalog.application.ReservationRepository
import com.ecommerce.catalog.domain.OrderId
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.catalog.domain.Quantity
import com.ecommerce.catalog.domain.Reservation
import com.ecommerce.catalog.domain.ReservationId
import com.ecommerce.catalog.domain.ReservationState
import com.ecommerce.catalog.domain.StockLine
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant
import java.util.UUID

/**
 * Outbound adapter: reservations in `reservations` and `reservation_lines` (V3__catalog_reservations.sql). The unique
 * `order_id` makes a second reservation of an order lose (`ON CONFLICT DO NOTHING`); state changes are conditional
 * on the version the caller read. Lines never change after creation.
 */
class R2dbcReservationRepository(
    private val database: DatabaseClient,
    private val transactions: TransactionalOperator,
) : ReservationRepository {
    override suspend fun find(id: ReservationId): Reservation? = select("WHERE id = :key", id.value).firstOrNull()

    override suspend fun findByOrder(orderId: OrderId): Reservation? =
        select("WHERE order_id = :key", orderId.value).firstOrNull()

    override suspend fun insert(reservation: Reservation): Boolean =
        transactions.executeAndAwait {
            val inserted =
                database
                    .sql(
                        "INSERT INTO reservations (id, order_id, state, restocked, created_at, expires_at, " +
                            "resolved_at, version) VALUES (:id, :orderId, :state, :restocked, :createdAt, " +
                            ":expiresAt, :resolvedAt, :version) ON CONFLICT (order_id) DO NOTHING",
                    ).bind("id", reservation.id.value)
                    .bind("orderId", reservation.orderId.value)
                    .bind("state", reservation.state.column())
                    .bind("restocked", reservation.restocked)
                    .bind("createdAt", reservation.createdAt)
                    .bind("expiresAt", reservation.expiresAt)
                    .bindNullable("resolvedAt", reservation.resolvedAt, Instant::class.java)
                    .bind("version", reservation.version)
                    .fetch()
                    .awaitRowsUpdated()
            if (inserted == 1L) insertLines(reservation)
            inserted == 1L
        }

    override suspend fun update(
        reservation: Reservation,
        expectedVersion: Long,
    ): Boolean =
        database
            .sql(
                "UPDATE reservations SET state = :state, restocked = :restocked, resolved_at = :resolvedAt, " +
                    "version = :version WHERE id = :id AND version = :expected",
            ).bind("state", reservation.state.column())
            .bind("restocked", reservation.restocked)
            .bindNullable("resolvedAt", reservation.resolvedAt, Instant::class.java)
            .bind("version", reservation.version)
            .bind("id", reservation.id.value)
            .bind("expected", expectedVersion)
            .fetch()
            .awaitRowsUpdated() == 1L

    override suspend fun expiring(
        now: Instant,
        limit: Int,
    ): List<Reservation> =
        select(
            "WHERE state = 'reserved' AND expires_at <= :key ORDER BY expires_at, id LIMIT $limit",
            now,
        )

    private suspend fun insertLines(reservation: Reservation) {
        reservation.lines.forEachIndexed { position, line ->
            database
                .sql(
                    "INSERT INTO reservation_lines (reservation_id, product_id, quantity, position) " +
                        "VALUES (:reservationId, :productId, :quantity, :position)",
                ).bind("reservationId", reservation.id.value)
                .bind("productId", line.productId.value)
                .bind("quantity", line.quantity.value)
                .bind("position", position)
                .fetch()
                .awaitRowsUpdated()
        }
    }

    private suspend fun select(
        condition: String,
        key: Any,
    ): List<Reservation> {
        val headers =
            database
                .sql("SELECT $COLUMNS FROM reservations $condition")
                .bind("key", key)
                .map(::headerOf)
                .flow()
                .toList()
        if (headers.isEmpty()) return emptyList()
        val lines =
            database
                .sql(
                    "SELECT reservation_id, product_id, quantity FROM reservation_lines " +
                        "WHERE reservation_id = ANY(:ids) ORDER BY reservation_id, position",
                ).bind("ids", headers.map { it.id }.toTypedArray())
                .map { row ->
                    row.required<UUID>("reservation_id") to
                        StockLine(
                            ProductId(row.required("product_id")),
                            Quantity.of(row.required<Int>("quantity")).trusted("quantity"),
                        )
                }.flow()
                .toList()
                .groupBy({ it.first }, { it.second })
        return headers.map { it.toReservation(lines[it.id].orEmpty()) }
    }

    /** One `reservations` row before its lines are attached. */
    @Suppress("LongParameterList") // the columns of the table
    private class Header(
        val id: UUID,
        val orderId: UUID,
        val state: String,
        val restocked: Boolean,
        val createdAt: Instant,
        val expiresAt: Instant,
        val resolvedAt: Instant?,
        val version: Long,
    ) {
        fun toReservation(lines: List<StockLine>): Reservation =
            Reservation(
                ReservationId(id),
                OrderId(orderId),
                lines,
                enumOf<ReservationState>(state),
                createdAt,
                expiresAt,
                resolvedAt,
                restocked,
                version,
            )
    }

    private companion object {
        const val COLUMNS = "id, order_id, state, restocked, created_at, expires_at, resolved_at, version"

        fun headerOf(row: Readable): Header =
            Header(
                id = row.required("id"),
                orderId = row.required("order_id"),
                state = row.required("state"),
                restocked = row.required("restocked"),
                createdAt = row.required("created_at"),
                expiresAt = row.required("expires_at"),
                resolvedAt = row.optional("resolved_at"),
                version = row.required("version"),
            )
    }
}
