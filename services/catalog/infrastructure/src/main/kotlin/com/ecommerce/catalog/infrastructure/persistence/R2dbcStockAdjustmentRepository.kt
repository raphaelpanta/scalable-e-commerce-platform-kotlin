package com.ecommerce.catalog.infrastructure.persistence

import com.ecommerce.catalog.application.StockAdjustmentRepository
import com.ecommerce.catalog.domain.StockAdjustment
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated

/** Outbound adapter: the append-only `stock_adjustments` table (V4__catalog_audit.sql). */
class R2dbcStockAdjustmentRepository(
    private val database: DatabaseClient,
) : StockAdjustmentRepository {
    override suspend fun record(adjustment: StockAdjustment) {
        database
            .sql(
                "INSERT INTO stock_adjustments (id, product_id, delta, reason, actor_id, adjusted_at, " +
                    "previous_available, new_available) " +
                    "VALUES (:id, :productId, :delta, :reason, :actorId, :at, :previous, :new)",
            ).bind("id", adjustment.id.value)
            .bind("productId", adjustment.productId.value)
            .bind("delta", adjustment.delta)
            .bind("reason", adjustment.reason.value)
            .bind("actorId", adjustment.actorId.value)
            .bind("at", adjustment.at)
            .bind("previous", adjustment.previousAvailable)
            .bind("new", adjustment.newAvailable)
            .fetch()
            .awaitRowsUpdated()
    }
}
