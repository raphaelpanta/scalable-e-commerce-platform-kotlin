package com.ecommerce.catalog.application

import arrow.core.Either
import com.ecommerce.catalog.domain.AccountId
import com.ecommerce.catalog.domain.CatalogError
import com.ecommerce.catalog.domain.Category
import com.ecommerce.catalog.domain.CategoryDetails
import com.ecommerce.catalog.domain.CategoryId
import com.ecommerce.catalog.domain.CategoryTree
import com.ecommerce.catalog.domain.InventoryLevel
import com.ecommerce.catalog.domain.OrderId
import com.ecommerce.catalog.domain.Page
import com.ecommerce.catalog.domain.PageRequest
import com.ecommerce.catalog.domain.Product
import com.ecommerce.catalog.domain.ProductDetails
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.catalog.domain.Quantity
import com.ecommerce.catalog.domain.ReleaseReason
import com.ecommerce.catalog.domain.Reservation
import com.ecommerce.catalog.domain.ReservationId
import com.ecommerce.catalog.domain.ReservationState
import com.ecommerce.catalog.domain.SaleState
import com.ecommerce.catalog.domain.Sku
import com.ecommerce.catalog.domain.StockAdjustment
import com.ecommerce.catalog.domain.StockEffect
import io.kotest.assertions.fail
import kotlinx.coroutines.yield
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

const val BRL = "BRL"
val NOW: Instant = Instant.parse("2026-10-02T10:00:00.123456Z")
val OPERATOR_ID = AccountId(UUID.fromString("e5a1c3b7-2d40-4f9e-8b16-3c7d9a0f1e22"))
val OPERATOR = Caller(OPERATOR_ID, setOf(CallerRole.OPERATOR))
val SHOPPER = Caller(AccountId(UUID.fromString("7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d")), setOf(CallerRole.SHOPPER))

fun <T> Either<CatalogError, T>.value(): T = fold({ fail("expected a value but was $it") }, { it })

fun <T> Either<CatalogError, T>.error(): CatalogError = fold({ it }, { fail("expected an error but was $it") })

fun <E, T> Either<E, T>.valid(): T = fold({ fail("invalid fixture: $it") }, { it })

/** Products in memory: SKUs are unique, updates check the version, listings filter and rank like the adapter. */
class InMemoryProducts(
    private val categories: InMemoryCategories,
) : ProductRepository {
    val products = linkedMapOf<ProductId, Product>()
    var losingUpdates = 0

    fun store(vararg stored: Product) = stored.forEach { products[it.id] = it }

    override suspend fun find(id: ProductId): Product? {
        yield()
        return products[id]
    }

    override suspend fun findAll(ids: Collection<ProductId>): List<Product> {
        yield()
        return ids.mapNotNull { products[it] }
    }

    override suspend fun page(
        filter: ProductFilter,
        request: PageRequest,
    ): Page<Product> {
        yield()
        val scope = filter.categoryId?.let { categories.subtree(it) }
        val matching =
            products.values
                .filter { filter.includeWithdrawn || it.isActive }
                .filter { scope == null || it.details.categoryId in scope }
                .mapNotNull { product ->
                    val rank =
                        filter.term?.let { it.rank(product.details.name.value, product.details.description?.value) }
                    if (filter.term != null && rank == null) null else product to (rank ?: 0)
                }.sortedWith(
                    compareBy({ it.second }, {
                        it.first.details.name.value
                            .lowercase()
                    }),
                ).map { it.first }
        val items = matching.drop(request.offset.toInt()).take(request.size)
        return Page(items, request.page, request.size, matching.size.toLong())
    }

    override suspend fun insert(product: Product): WriteResult {
        yield()
        if (products.values.any { it.sku == product.sku }) return WriteResult.DUPLICATE
        products[product.id] = product
        return WriteResult.WRITTEN
    }

    override suspend fun update(
        product: Product,
        expectedVersion: Long,
    ): WriteResult {
        yield()
        if (losingUpdates > 0 || products[product.id]?.version != expectedVersion) {
            losingUpdates--
            return WriteResult.STALE
        }
        products[product.id] = product
        return WriteResult.WRITTEN
    }
}

/** Categories in memory: names are unique per parent (case-insensitively), updates check the version. */
class InMemoryCategories : CategoryRepository {
    val categories = linkedMapOf<CategoryId, Category>()

    fun store(vararg stored: Category) = stored.forEach { categories[it.id] = it }

    fun subtree(root: CategoryId): Set<CategoryId> =
        categories.keys.filter { root in CategoryTree.ancestry(it, parents()) }.toSet()

    private fun parents(): Map<CategoryId, CategoryId?> = categories.mapValues { it.value.details.parentId }

    override suspend fun find(id: CategoryId): Category? {
        yield()
        return categories[id]
    }

    override suspend fun page(
        parentId: CategoryId?,
        request: PageRequest,
    ): Page<Category> {
        yield()
        val matching =
            categories.values
                .filter { parentId == null || it.details.parentId == parentId }
                .sortedBy { it.details.name.value }
        return Page(
            matching.drop(request.offset.toInt()).take(request.size),
            request.page,
            request.size,
            matching.size.toLong(),
        )
    }

    override suspend fun hierarchy(): Map<CategoryId, CategoryId?> {
        yield()
        return parents()
    }

    override suspend fun insert(category: Category): WriteResult {
        yield()
        if (clashes(category)) return WriteResult.DUPLICATE
        categories[category.id] = category
        return WriteResult.WRITTEN
    }

    override suspend fun update(
        category: Category,
        expectedVersion: Long,
    ): WriteResult {
        yield()
        return when {
            categories[category.id]?.version != expectedVersion -> WriteResult.STALE
            clashes(category) -> WriteResult.DUPLICATE
            else -> WriteResult.WRITTEN.also { categories[category.id] = category }
        }
    }

    private fun clashes(category: Category): Boolean =
        categories.values.any {
            it.id != category.id &&
                it.details.parentId == category.details.parentId &&
                it.details.name.value
                    .equals(category.details.name.value, ignoreCase = true)
        }
}

/** Stock levels in memory with the guards of the SQL statements; switches make guarded updates fail. */
class InMemoryInventory : InventoryRepository {
    val levels = linkedMapOf<ProductId, InventoryLevel>()
    var failingReservations = 0
    var failingApplies = 0
    var failingAdjustments = 0

    fun store(vararg stored: InventoryLevel) = stored.forEach { levels[it.productId] = it }

    override suspend fun find(productId: ProductId): InventoryLevel? {
        yield()
        return levels[productId]
    }

    override suspend fun findAll(productIds: Collection<ProductId>): Map<ProductId, InventoryLevel> {
        yield()
        return levels.filterKeys { it in productIds }
    }

    override suspend fun insert(level: InventoryLevel) {
        yield()
        levels[level.productId] = level
    }

    override suspend fun tryReserve(
        productId: ProductId,
        quantity: Quantity,
    ): Boolean {
        yield()
        val level = levels[productId]
        if (level == null || failingReservations > 0) {
            failingReservations--
            return false
        }
        return level.reserve(quantity).map { levels[productId] = it }.isRight()
    }

    override suspend fun apply(
        productId: ProductId,
        effect: StockEffect,
        quantity: Quantity,
    ): Boolean {
        yield()
        val level = levels[productId]
        if (level == null || failingApplies > 0) {
            failingApplies--
            return false
        }
        levels[productId] = level.apply(effect, quantity)
        return true
    }

    override suspend fun adjust(
        productId: ProductId,
        delta: Int,
    ): InventoryLevel? {
        yield()
        if (failingAdjustments > 0) {
            failingAdjustments--
            return null
        }
        return levels[productId]?.adjust(delta)?.getOrNull()?.also { levels[productId] = it }
    }
}

/** Reservations in memory: one per order, updates check the version; switches lose races. */
class InMemoryReservations : ReservationRepository {
    val reservations = linkedMapOf<ReservationId, Reservation>()
    var losingInserts = 0
    var losingUpdates = 0
    var expiringLimit: Int? = null

    fun store(vararg stored: Reservation) = stored.forEach { reservations[it.id] = it }

    override suspend fun find(id: ReservationId): Reservation? {
        yield()
        return reservations[id]
    }

    override suspend fun findByOrder(orderId: OrderId): Reservation? {
        yield()
        return reservations.values.firstOrNull { it.orderId == orderId }
    }

    override suspend fun insert(reservation: Reservation): Boolean {
        yield()
        if (losingInserts > 0 || reservations.values.any { it.orderId == reservation.orderId }) {
            losingInserts--
            return false
        }
        reservations[reservation.id] = reservation
        return true
    }

    override suspend fun update(
        reservation: Reservation,
        expectedVersion: Long,
    ): Boolean {
        yield()
        if (losingUpdates > 0 || reservations[reservation.id]?.version != expectedVersion) {
            losingUpdates--
            return false
        }
        reservations[reservation.id] = reservation
        return true
    }

    override suspend fun expiring(
        now: Instant,
        limit: Int,
    ): List<Reservation> {
        yield()
        expiringLimit = limit
        return reservations.values
            .filter { it.state == ReservationState.RESERVED && !it.expiresAt.isAfter(now) }
            .sortedBy { it.expiresAt }
            .take(limit)
    }
}

/** Every event published, as `type:reservationId[:reason]` plus the correlation ids. */
class RecordedStockEvents : StockEvents {
    val published = mutableListOf<String>()
    val correlationIds = mutableListOf<String?>()

    override suspend fun reserved(
        reservation: Reservation,
        correlationId: String?,
    ) = record("reserved:${reservation.id.value}", correlationId)

    override suspend fun committed(
        reservation: Reservation,
        correlationId: String?,
    ) = record("committed:${reservation.id.value}", correlationId)

    override suspend fun released(
        reservation: Reservation,
        reason: ReleaseReason,
        correlationId: String?,
    ) = record("released:${reservation.id.value}:$reason", correlationId)

    private suspend fun record(
        event: String,
        correlationId: String?,
    ) {
        yield()
        published += event
        correlationIds += correlationId
    }
}

/** Every audit line, as `refused:action:target` or `changed:actor:action:target`. */
class RecordedAudit : AuditLog {
    val lines = mutableListOf<String>()

    override suspend fun refused(
        caller: Caller,
        action: String,
        target: UUID?,
    ) {
        lines += "refused:${caller.accountId?.value}:$action:$target"
    }

    override suspend fun changed(
        actor: AccountId,
        action: String,
        target: UUID,
    ) {
        lines += "changed:${actor.value}:$action:$target"
    }
}

/** Runs the block and restores every store when it returns an error, like a rolled-back transaction. */
class FakeTransactions(
    private val harness: Harness,
) : Transactions {
    var rollbacks = 0

    override suspend fun <T> inTransaction(block: suspend () -> Either<CatalogError, T>): Either<CatalogError, T> {
        yield()
        val products = LinkedHashMap(harness.products.products)
        val levels = LinkedHashMap(harness.inventory.levels)
        val reservations = LinkedHashMap(harness.reservations.reservations)
        val adjustments = harness.adjustments.toList()
        val events = harness.events.published.toList()
        return block().onLeft {
            rollbacks++
            harness.products.products
                .apply { clear() }
                .putAll(products)
            harness.inventory.levels
                .apply { clear() }
                .putAll(levels)
            harness.reservations.reservations
                .apply { clear() }
                .putAll(reservations)
            harness.adjustments.apply { clear() }.addAll(adjustments)
            harness.events.published
                .apply { clear() }
                .addAll(events)
        }
    }
}

/** Everything a use case needs, in memory, at a fixed clock and with sequential ids. */
class Harness {
    val categories = InMemoryCategories()
    val products = InMemoryProducts(categories)
    val inventory = InMemoryInventory()
    val reservations = InMemoryReservations()
    val adjustments = mutableListOf<StockAdjustment>()
    val events = RecordedStockEvents()
    val audit = RecordedAudit()
    val transactions = FakeTransactions(this)
    private var nextId = 0L
    val catalog =
        Catalog(
            products,
            categories,
            inventory,
            reservations,
            { adjustments += it },
            events,
            transactions,
            audit,
            CatalogSettings(BRL, Reservation.DEFAULT_TTL, Clock.fixed(NOW, ZoneOffset.UTC)) { UUID(0L, ++nextId) },
        )

    fun category(
        name: String = "Kitchen",
        parentId: CategoryId? = null,
    ): Category =
        Category
            .create(CategoryId(UUID.randomUUID()), CategoryDetails.of(name, null, parentId).valid(), NOW)
            .also { categories.store(it) }

    @Suppress("LongParameterList") // every attribute of a stored product can be varied
    fun product(
        name: String = "Espresso Machine",
        stock: Int = 10,
        reserved: Int = 0,
        saleState: SaleState = SaleState.ACTIVE,
        categoryId: CategoryId = category().id,
        description: String? = null,
    ): Product {
        val id = ProductId(UUID.randomUUID())
        val details = ProductDetails.of(name, description, PRICE, BRL, categoryId).valid()
        val product = Product.create(id, Sku.generatedFor(id), details, NOW).copy(saleState = saleState)
        products.store(product)
        inventory.store(InventoryLevel(id, stock, reserved))
        return product
    }

    fun level(product: Product): InventoryLevel = inventory.levels.getValue(product.id)

    /** The catalog of this harness with some ports replaced (to stage races). */
    fun catalogWith(
        inventory: InventoryRepository = this.inventory,
        reservations: ReservationRepository = this.reservations,
    ): Catalog =
        Catalog(
            products,
            categories,
            inventory,
            reservations,
            catalog.adjustments,
            events,
            transactions,
            audit,
            catalog.settings,
        )

    companion object {
        const val PRICE = 14900L
    }
}
