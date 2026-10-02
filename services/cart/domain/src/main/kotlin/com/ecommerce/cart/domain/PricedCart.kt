package com.ecommerce.cart.domain

import java.security.MessageDigest
import java.util.HexFormat

/**
 * Opaque cart revision (FR-010, ADR 0003): printable ASCII, compared for equality only. It is derived, never stored:
 * a digest of the cart id, its version (one more on every mutation) and each line's product, quantity and current
 * unit price, so it changes whenever a line, a quantity or a current price changes and stays the same otherwise.
 */
@JvmInline
value class CartRevision(
    val value: String,
) {
    companion object {
        private const val PREFIX = "rev-"
        private const val DIGEST_BYTES = 16

        /** The revision of version [version] of cart [cartId] whose lines are priced as [lines]. */
        fun derive(
            cartId: CartId,
            version: Long,
            lines: List<PricedLine>,
        ): CartRevision {
            val canonical =
                lines
                    .map { "${it.line.productId.value}:${it.line.quantity.value}:${it.currentPrice.canonical()}" }
                    .sorted()
                    .joinToString(separator = ";", prefix = "${cartId.value}|$version|")
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return CartRevision(PREFIX + HexFormat.of().formatHex(digest, 0, DIGEST_BYTES))
        }

        private fun Money.canonical(): String = "$amountMinor$currency"
    }
}

/** A line with the catalogue's [currentPrice] (the price at add when the catalogue no longer knows the product). */
data class PricedLine(
    val line: CartLine,
    val currentPrice: Money,
) {
    /** True when the current price differs from the price at add. */
    val priceChanged: Boolean get() = currentPrice != line.priceAtAdd

    /** `quantity x currentPrice`. */
    val lineTotal: Money get() = currentPrice * line.quantity
}

/** A cart as shown to the shopper: lines at current prices, the [total] of their line totals and the [revision]. */
data class PricedCart(
    val cart: Cart,
    val lines: List<PricedLine>,
    val total: Money,
    val revision: CartRevision,
)

/** This cart priced with the live [quotes] of its products; [currency] is the platform currency of the total. */
fun Cart.priced(
    quotes: Map<ProductId, ProductQuote>,
    currency: String,
): PricedCart {
    val priced = lines.map { PricedLine(it, quotes[it.productId]?.price ?: it.priceAtAdd) }
    val total = Money.sum(priced.map { it.lineTotal }, currency)
    return PricedCart(this, priced, total, CartRevision.derive(id, version, priced))
}
