package com.ecommerce.catalog.infrastructure.persistence

import com.ecommerce.catalog.application.InventoryRepository
import com.ecommerce.catalog.domain.InventoryLevel
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.catalog.domain.Quantity
import com.ecommerce.catalog.domain.StockEffect
import com.ecommerce.catalog.domain.StockLevel
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow

/**
 * Outbound adapter: one stock level per product in `inventory_levels` (V2__catalog_schema.sql). Every change is a
 * single guarded `UPDATE`: the row lock serialises concurrent writers and the `WHERE` clause re-checks the invariant
 * on the latest committed row, so of two reservations of the last unit exactly one updates a row (FR-012, SC-004).
 */
class R2dbcInventoryRepository(
    private val database: DatabaseClient,
) : InventoryRepository {
    override suspend fun find(productId: ProductId): InventoryLevel? =
        database
            .sql("SELECT $COLUMNS FROM inventory_levels WHERE product_id = :id")
            .bind("id", productId.value)
            .map(::levelOf)
            .awaitOneOrNull()

    override suspend fun findAll(productIds: Collection<ProductId>): Map<ProductId, InventoryLevel> =
        if (productIds.isEmpty()) {
            emptyMap()
        } else {
            database
                .sql("SELECT $COLUMNS FROM inventory_levels WHERE product_id = ANY(:ids)")
                .bind("ids", productIds.map { it.value }.toTypedArray())
                .map(::levelOf)
                .flow()
                .toList()
                .associateBy(InventoryLevel::productId)
        }

    override suspend fun insert(level: InventoryLevel) {
        database
            .sql(
                "INSERT INTO inventory_levels (product_id, on_hand, reserved, version, updated_at) " +
                    "VALUES (:id, :onHand, :reserved, :version, now())",
            ).bind("id", level.productId.value)
            .bind("onHand", level.onHand)
            .bind("reserved", level.reserved)
            .bind("version", level.version)
            .fetch()
            .awaitRowsUpdated()
    }

    override suspend fun tryReserve(
        productId: ProductId,
        quantity: Quantity,
    ): Boolean =
        database
            .sql(
                "UPDATE inventory_levels SET reserved = reserved + :q, version = version + 1, updated_at = now() " +
                    "WHERE product_id = :id AND on_hand - reserved >= :q",
            ).bind("q", quantity.value)
            .bind("id", productId.value)
            .fetch()
            .awaitRowsUpdated() == 1L

    override suspend fun apply(
        productId: ProductId,
        effect: StockEffect,
        quantity: Quantity,
    ): Boolean =
        database
            .sql(
                "UPDATE inventory_levels SET on_hand = on_hand + :onHand, reserved = reserved + :reserved, " +
                    "version = version + 1, updated_at = now() WHERE product_id = :id " +
                    "AND reserved + :reserved >= 0 AND on_hand + :onHand >= reserved + :reserved",
            ).bind("onHand", effect.onHandDelta(quantity.value))
            .bind("reserved", effect.reservedDelta(quantity.value))
            .bind("id", productId.value)
            .fetch()
            .awaitRowsUpdated() == 1L

    override suspend fun adjust(
        productId: ProductId,
        delta: Int,
    ): InventoryLevel? =
        database
            .sql(
                "UPDATE inventory_levels SET on_hand = on_hand + :delta, version = version + 1, updated_at = now() " +
                    "WHERE product_id = :id AND on_hand + :delta >= reserved AND on_hand + :delta <= :max " +
                    "RETURNING $COLUMNS",
            ).bind("delta", delta)
            .bind("id", productId.value)
            .bind("max", StockLevel.MAX)
            .map(::levelOf)
            .awaitOneOrNull()

    private companion object {
        const val COLUMNS = "product_id, on_hand, reserved, version"

        fun levelOf(row: Readable): InventoryLevel =
            InventoryLevel(
                ProductId(row.required("product_id")),
                row.required("on_hand"),
                row.required("reserved"),
                row.required("version"),
            )
    }
}
