package com.ecommerce.cart.domain

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import java.time.Instant
import java.util.UUID

/** Who a cart belongs to: a signed-in account, or the holder of an anonymous token (only its hash is kept). */
sealed interface CartOwner {
    data class Account(
        val accountId: AccountId,
    ) : CartOwner

    data class Anonymous(
        val tokenHash: String,
    ) : CartOwner
}

/**
 * One product in a cart with the snapshots taken when it was added (data-model section 3.3): [sku], [name] and the
 * unit price [priceAtAdd]. The current price is never stored; it is read live from the catalogue.
 */
data class CartLine(
    val id: LineId,
    val productId: ProductId,
    val sku: String,
    val name: String,
    val quantity: Quantity,
    val priceAtAdd: Money,
    val addedAt: Instant,
)

/** A product and the quantity of it an order bought (`OrderPaid` lines). */
data class OrderedItem(
    val productId: ProductId,
    val quantity: Int,
)

/**
 * The cart aggregate (data-model section 3.3): at most [MAX_LINES] lines, one per product, each 1..99 units and never
 * more than the stock available when it was set. Every mutation returns a new cart whose [version] is one higher, so
 * the derived [CartRevision] changes with every mutation; [version] is also the optimistic-locking token.
 */
data class Cart(
    val id: CartId,
    val owner: CartOwner,
    val lines: List<CartLine>,
    val version: Long,
    val updatedAt: Instant,
) {
    /** The products of the lines. */
    val productIds: Set<ProductId> get() = lines.mapTo(linkedSetOf()) { it.productId }

    /** The line [lineId], if the cart has it. */
    fun line(lineId: LineId): CartLine? = lines.firstOrNull { it.id == lineId }

    /** The line of [productId], if the cart has one. */
    fun lineFor(productId: ProductId): CartLine? = lines.firstOrNull { it.productId == productId }

    /**
     * Adds [quantity] units of the quoted product: a product already in the cart has its quantity increased and its
     * snapshots (price, name, sku) refreshed; a new product becomes line [newLineId]. The resulting quantity must be
     * sellable now ([ProductQuote.allow]) and a new line must fit in [MAX_LINES].
     */
    fun add(
        quote: ProductQuote,
        quantity: Quantity,
        newLineId: LineId,
        now: Instant,
    ): Either<CartError, Cart> =
        either {
            val existing = lineFor(quote.productId)
            ensure(existing != null || lines.size < MAX_LINES) { CartError.TooManyLines }
            val wanted = quote.allow((existing?.quantity?.value ?: 0) + quantity.value).bind()
            if (existing == null) {
                val added = CartLine(newLineId, quote.productId, quote.sku, quote.name, wanted, quote.price, now)
                changed(lines + added, now)
            } else {
                val refreshed =
                    existing.copy(quantity = wanted, sku = quote.sku, name = quote.name, priceAtAdd = quote.price)
                changed(lines.map { if (it.id == existing.id) refreshed else it }, now)
            }
        }

    /** Sets line [lineId] to [quantity] units, which must be sellable now ([ProductQuote.allow]). */
    fun changeQuantity(
        lineId: LineId,
        quantity: Int,
        quote: ProductQuote,
        now: Instant,
    ): Either<CartError, Cart> =
        either {
            ensure(line(lineId) != null) { CartError.LineNotFound(lineId) }
            val wanted = quote.allow(quantity).bind()
            changed(lines.map { if (it.id == lineId) it.copy(quantity = wanted) else it }, now)
        }

    /** Removes line [lineId]. */
    fun remove(
        lineId: LineId,
        now: Instant,
    ): Either<CartError, Cart> =
        either {
            ensure(line(lineId) != null) { CartError.LineNotFound(lineId) }
            changed(lines.filterNot { it.id == lineId }, now)
        }

    /** Removes every line. */
    fun clear(now: Instant): Cart = changed(emptyList(), now)

    /**
     * Takes the [ordered] quantities out of the cart (payment approved, `OrderPaid`): a line loses the units bought
     * and disappears when none remain. The cart is returned unchanged (same version) when nothing was bought from it.
     */
    fun removeOrdered(
        ordered: List<OrderedItem>,
        now: Instant,
    ): Cart {
        val bought = ordered.groupingBy { it.productId }.fold(0) { sum, item -> sum + item.quantity }
        val remaining =
            lines.mapNotNull { line ->
                val units = bought[line.productId] ?: 0
                if (units == 0) {
                    line
                } else {
                    Quantity.of(line.quantity.value - units).getOrNull()?.let { line.copy(quantity = it) }
                }
            }
        return if (remaining == lines) this else changed(remaining, now)
    }

    /** The sum of `quantity x priceAtAdd` of the lines (informational total of the internal cart). */
    fun totalAtAdd(currency: String): Money = Money.sum(lines.map { it.priceAtAdd * it.quantity }, currency)

    /** This cart after a mutation: [newLines], one version more, updated [now]. */
    internal fun changed(
        newLines: List<CartLine>,
        now: Instant,
    ): Cart = copy(lines = newLines, version = version + 1, updatedAt = now)

    companion object {
        /** Most lines one cart may hold. */
        const val MAX_LINES: Int = 100

        /** Id of the cart shown to a caller who has none yet (anonymous without token, account without cart). */
        val NO_CART: CartId = CartId(UUID(0, 0))

        /** A new, empty cart of [owner] (version 0, not stored yet). */
        fun new(
            id: CartId,
            owner: CartOwner,
            now: Instant,
        ): Cart = Cart(id, owner, emptyList(), 0, now)
    }
}
