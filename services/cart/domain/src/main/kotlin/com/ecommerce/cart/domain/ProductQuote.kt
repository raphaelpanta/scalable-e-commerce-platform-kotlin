package com.ecommerce.cart.domain

import arrow.core.Either
import arrow.core.left

/** Whether the catalogue sells a product (catalog-internal.yaml `saleState`). */
enum class SaleState {
    ACTIVE,
    WITHDRAWN,
}

/**
 * The live catalogue view of a product (catalog pricing endpoints): its current [price], the stock [available] now
 * (`onHand - reserved`) and its [saleState], plus the [sku] and [name] snapshots a line keeps. Read on every cart
 * view and before every change; never stored (data-model section 3.3, read-model copies).
 */
data class ProductQuote(
    val productId: ProductId,
    val sku: String,
    val name: String,
    val price: Money,
    val available: Int,
    val saleState: SaleState,
) {
    /** The highest quantity a line of this product may hold now: 0 when withdrawn, never above [Quantity.MAX]. */
    fun sellable(): Int = if (saleState == SaleState.ACTIVE) available.coerceIn(0, Quantity.MAX) else 0

    /**
     * [wanted] units as a line quantity: refused when the product is withdrawn ([CartError.ProductUnavailable]), when
     * fewer units are available ([CartError.InsufficientStock], naming the available quantity) or when [wanted] is
     * outside 1..99 ([CartError.InvalidQuantity]).
     */
    fun allow(wanted: Int): Either<CartError, Quantity> =
        when {
            saleState != SaleState.ACTIVE -> CartError.ProductUnavailable(productId).left()
            wanted > available -> CartError.InsufficientStock(productId, wanted, available).left()
            else -> Quantity.of(wanted)
        }
}
