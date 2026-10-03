package com.ecommerce.catalog.application

import com.ecommerce.catalog.domain.AccountId
import com.ecommerce.catalog.domain.Category
import com.ecommerce.catalog.domain.CategoryId
import com.ecommerce.catalog.domain.CategoryStatus
import com.ecommerce.catalog.domain.Product
import com.ecommerce.catalog.domain.Quantity
import com.ecommerce.catalog.domain.SaleState
import com.ecommerce.catalog.domain.StockLevel
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.uuid
import java.time.Duration

/** The stock of one generated product: `0 <= reserved <= onHand`, plus whether it is on sale. */
data class StockSpec(
    val onHand: Int,
    val reserved: Int,
    val saleState: SaleState,
) {
    val available: Int get() = onHand - reserved
}

/** One generated catalogue entry: a [name], a [description], its [stock] and the index of its category. */
data class ProductSpec(
    val name: String,
    val description: String?,
    val stock: StockSpec,
    val category: Int,
)

/**
 * A generated catalogue: a forest of categories (each one a root or beneath an earlier one, never deeper than the
 * category limit) of which some are withdrawn, and products spread over them.
 */
data class ShelfSpec(
    val parents: List<Int?>,
    val withdrawnCategories: Set<Int>,
    val products: List<ProductSpec>,
)

/** A step applied to a stored reservation by the order service or the expiry job. */
sealed interface ReservationStep {
    data object Commit : ReservationStep

    data object Release : ReservationStep

    data class Settle(
        val outcome: OrderOutcome,
    ) : ReservationStep

    data object Expire : ReservationStep
}

/**
 * Kotest generators for the catalogue's application tests (Constitution V): callers, stock levels, carts (order
 * lines), catalogues with withdrawn products and categories, reservation life cycles and page requests.
 */
object CatalogArbs {
    private const val MAX_STOCK = 200
    private const val MAX_PRODUCTS = 12
    private const val MAX_CATEGORIES = 6
    private const val MAX_PAGE_SIZE = 7
    private const val MAX_STEPS = 8
    private val WORDS = listOf("lantern", "lamp", "desk", "mug", "kettle", "stove", "tent", "Lantern", "MUG")

    /** Shoppers and anonymous callers: anybody without the operator role. */
    val nonOperator: Arb<Caller> =
        Arb.choice(
            Arb.uuid().map { Caller(AccountId(it), setOf(CallerRole.SHOPPER)) },
            Arb.uuid().map { Caller(AccountId(it), emptySet()) },
            Arb.constant(Caller.ANONYMOUS),
            Arb.constant(Caller(null, setOf(CallerRole.OPERATOR))),
        )

    val operator: Arb<Caller> =
        Arb.uuid().map { Caller(AccountId(it), setOf(CallerRole.OPERATOR, CallerRole.SHOPPER)) }

    val caller: Arb<Caller> = Arb.choice(nonOperator, operator)

    val stock: Arb<StockSpec> =
        Arb.int(0..MAX_STOCK).flatMap { onHand ->
            Arb.bind(
                Arb.int(0..onHand),
                Arb.enum<SaleState>(),
            ) { reserved, state -> StockSpec(onHand, reserved, state) }
        }

    /** Stock of a product on sale. */
    val activeStock: Arb<StockSpec> = stock.map { it.copy(saleState = SaleState.ACTIVE) }

    val quantity: Arb<Int> = Arb.int(Quantity.MIN..Quantity.MAX)

    /** A signed stock adjustment: mostly small, sometimes beyond the limits, never 0. */
    val delta: Arb<Int> =
        Arb
            .choice(
                Arb.int(-MAX_STOCK..MAX_STOCK),
                Arb.int(-StockLevel.MAX..StockLevel.MAX),
            ).map { if (it == 0) 1 else it }

    val outcome: Arb<OrderOutcome> = Arb.enum<OrderOutcome>()

    val step: Arb<ReservationStep> =
        Arb.choice(
            Arb.constant(ReservationStep.Commit),
            Arb.constant(ReservationStep.Release),
            outcome.map(ReservationStep::Settle),
            Arb.constant(ReservationStep.Expire),
        )

    val steps: Arb<List<ReservationStep>> = Arb.list(step, 1..MAX_STEPS)

    /** The lifetime of a reservation opened an hour ago: lapsed (30 minutes) or still open (two hours). */
    val lifetime: Arb<Duration> = Arb.element(Duration.ofMinutes(30), Duration.ofHours(2))

    /** A cart of order lines over [products] product slots: distinct slots, 1..99 units each. */
    fun cart(products: Int): Arb<List<Pair<Int, Int>>> =
        Arb.list(Arb.bind(Arb.int(0 until products), quantity) { slot, units -> slot to units }, 1..products).map {
            it.distinctBy(Pair<Int, Int>::first)
        }

    val productName: Arb<String> =
        Arb
            .list(Arb.element(WORDS), 1..3)
            .map { it.joinToString(" ") }

    /** Indices of parents: category `i` is a root or sits beneath category `i - 1` (at most four levels deep). */
    private val forest: Arb<List<Int?>> =
        Arb.int(1..MAX_CATEGORIES).flatMap { size ->
            Arb.list(Arb.boolean(), size..size).map { nested ->
                nested.mapIndexed { index, beneath -> if (beneath && index % CHAIN != 0) index - 1 else null }
            }
        }

    val shelf: Arb<ShelfSpec> =
        forest.flatMap { parents ->
            Arb.bind(
                Arb.list(Arb.int(parents.indices), 0..parents.size).map { it.toSet() },
                Arb.list(
                    Arb.bind(productName, productName.orNull(), stock, Arb.int(parents.indices), ::ProductSpec),
                    0..MAX_PRODUCTS,
                ),
            ) { withdrawn, products -> ShelfSpec(parents, withdrawn, products) }
        }

    val pageSize: Arb<Int> = Arb.int(1..MAX_PAGE_SIZE)

    /** Chains restart every four categories, so no branch is deeper than the category limit. */
    private const val CHAIN = 4
}

/** A [ShelfSpec] stored in a harness: its categories and products, in generation order. */
data class Shelf(
    val categories: List<Category>,
    val products: List<Product>,
) {
    /** The categories hidden from shoppers, computed independently: withdrawn or beneath a withdrawn one. */
    fun hidden(spec: ShelfSpec): Set<CategoryId> {
        val hidden = mutableSetOf<Int>()
        spec.parents.forEachIndexed { index, parent ->
            if (index in spec.withdrawnCategories || (parent != null && parent in hidden)) hidden += index
        }
        return hidden.map { categories[it].id }.toSet()
    }

    /** The products a shopper may see and buy. */
    fun onSale(spec: ShelfSpec): List<Product> {
        val hidden = hidden(spec)
        return products.filter { it.isActive && it.details.categoryId !in hidden }
    }
}

/** Stores [spec]: categories named `C<index>`, products with their stock. */
fun Harness.shelve(spec: ShelfSpec): Shelf {
    val categories = mutableListOf<Category>()
    spec.parents.forEachIndexed { index, parent ->
        val status = if (index in spec.withdrawnCategories) CategoryStatus.WITHDRAWN else CategoryStatus.ACTIVE
        categories += category("C$index", parent?.let { categories[it].id }, status)
    }
    val products =
        spec.products.map {
            product(
                it.name,
                it.stock.onHand,
                it.stock.reserved,
                it.stock.saleState,
                categories[it.category].id,
                it.description,
            )
        }
    return Shelf(categories, products)
}
