package com.ecommerce.catalog.application

import arrow.core.Either
import com.ecommerce.catalog.domain.CatalogError
import com.ecommerce.catalog.domain.Category
import com.ecommerce.catalog.domain.CategoryId
import com.ecommerce.catalog.domain.InventoryLevel
import com.ecommerce.catalog.domain.OrderId
import com.ecommerce.catalog.domain.Page
import com.ecommerce.catalog.domain.PageRequest
import com.ecommerce.catalog.domain.Product
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.catalog.domain.Quantity
import com.ecommerce.catalog.domain.ReleaseReason
import com.ecommerce.catalog.domain.Reservation
import com.ecommerce.catalog.domain.ReservationId
import com.ecommerce.catalog.domain.SearchTerm
import com.ecommerce.catalog.domain.StockAdjustment
import com.ecommerce.catalog.domain.StockEffect
import java.time.Instant

/** Outcome of an insert or a version-checked update. */
enum class WriteResult {
    WRITTEN,

    /** A unique rule refused the row (SKU, category name per parent). */
    DUPLICATE,

    /** The row moved on since it was read (optimistic locking) or is gone. */
    STALE,
}

/**
 * Which products a listing returns: those of [categoryId] and its descendants (every category when null), withdrawn
 * ones only when [includeWithdrawn], none of the [hiddenCategories] (withdrawn categories and their descendants), and
 * with a [term] only matching ones ranked by relevance (otherwise by name).
 */
data class ProductFilter(
    val categoryId: CategoryId? = null,
    val includeWithdrawn: Boolean = false,
    val term: SearchTerm? = null,
    val hiddenCategories: Set<CategoryId> = emptySet(),
)

/** Outbound port: products with their images. */
interface ProductRepository {
    suspend fun find(id: ProductId): Product?

    suspend fun findAll(ids: Collection<ProductId>): List<Product>

    suspend fun page(
        filter: ProductFilter,
        request: PageRequest,
    ): Page<Product>

    /** Stores a new product; [WriteResult.DUPLICATE] when its SKU is taken. */
    suspend fun insert(product: Product): WriteResult

    /** Replaces a product (images included) still at [expectedVersion]. */
    suspend fun update(
        product: Product,
        expectedVersion: Long,
    ): WriteResult
}

/** Outbound port: categories. */
interface CategoryRepository {
    suspend fun find(id: CategoryId): Category?

    /** Categories ordered by name except the [excluded] ones; only the children of [parentId] when given. */
    suspend fun page(
        parentId: CategoryId?,
        request: PageRequest,
        excluded: Set<CategoryId> = emptySet(),
    ): Page<Category>

    /** The parent of every category (the tree, for placement checks). */
    suspend fun hierarchy(): Map<CategoryId, CategoryId?>

    /** The ids of the withdrawn categories. */
    suspend fun withdrawn(): Set<CategoryId>

    /** Stores a new category; [WriteResult.DUPLICATE] when its name is taken under the same parent. */
    suspend fun insert(category: Category): WriteResult

    suspend fun update(
        category: Category,
        expectedVersion: Long,
    ): WriteResult
}

/** Outbound port: stock levels, changed only through guarded (conditional) updates. */
interface InventoryRepository {
    suspend fun find(productId: ProductId): InventoryLevel?

    suspend fun findAll(productIds: Collection<ProductId>): Map<ProductId, InventoryLevel>

    suspend fun insert(level: InventoryLevel)

    /** `reserved += quantity` only while `onHand - reserved >= quantity` (row-level guard); false otherwise. */
    suspend fun tryReserve(
        productId: ProductId,
        quantity: Quantity,
    ): Boolean

    /** Moves stock by [effect] for [quantity] units unless that breaks `0 <= reserved <= onHand`; false otherwise. */
    suspend fun apply(
        productId: ProductId,
        effect: StockEffect,
        quantity: Quantity,
    ): Boolean

    /** `onHand += delta` only while the available quantity stays non-negative; the new level, or null. */
    suspend fun adjust(
        productId: ProductId,
        delta: Int,
    ): InventoryLevel?
}

/** Outbound port: stock reservations, one per order. */
interface ReservationRepository {
    suspend fun find(id: ReservationId): Reservation?

    suspend fun findByOrder(orderId: OrderId): Reservation?

    /** Stores a new reservation; false when its order has one already. */
    suspend fun insert(reservation: Reservation): Boolean

    /** Replaces a reservation still at [expectedVersion]; false when it moved on. */
    suspend fun update(
        reservation: Reservation,
        expectedVersion: Long,
    ): Boolean

    /** Up to [limit] reservations still `reserved` whose expiry is at or before [now], oldest first. */
    suspend fun expiring(
        now: Instant,
        limit: Int,
    ): List<Reservation>
}

/** Outbound port: the append-only record of stock adjustments. */
fun interface StockAdjustmentRepository {
    suspend fun record(adjustment: StockAdjustment)
}

/**
 * Outbound port: the stock events (events.yaml `catalog.stock.v1`), published through the transactional outbox
 * inside the caller's transaction. [correlationId] is the originating one when known (consumed events).
 */
interface StockEvents {
    suspend fun reserved(
        reservation: Reservation,
        correlationId: String?,
    )

    suspend fun committed(
        reservation: Reservation,
        correlationId: String?,
    )

    suspend fun released(
        reservation: Reservation,
        reason: ReleaseReason,
        correlationId: String?,
    )
}

/** Outbound port: runs a block in one database transaction, rolled back when the block returns an error. */
interface Transactions {
    suspend fun <T> inTransaction(block: suspend () -> Either<CatalogError, T>): Either<CatalogError, T>
}

/**
 * Outbound port: the durable, append-only audit trail of operator actions (account ids only, never personal data).
 * Entries of performed changes are recorded inside the change's transaction.
 */
fun interface AuditLog {
    suspend fun record(entry: AuditEntry)
}
