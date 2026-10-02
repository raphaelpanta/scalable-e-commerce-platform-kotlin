package com.ecommerce.order.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.security.MessageDigest
import java.util.HexFormat

/** One line of the shopper's cart as the cart context reports it (`priceAtAdd` is informational). */
data class CartLine(
    val lineId: String,
    val productId: ProductId,
    val sku: String,
    val name: String,
    val quantity: Quantity,
    val priceAtAdd: Money,
)

/** The shopper's cart with its opaque [revision] (ADR 0003). */
data class Cart(
    val revision: String,
    val lines: List<CartLine>,
)

/** Current price, name and sale state of a product (catalog pricing). */
data class ProductPrice(
    val productId: ProductId,
    val sku: String,
    val name: String,
    val price: Money,
    val available: Int,
    val active: Boolean,
)

/** Quantity of one product to reserve. */
data class StockLine(
    val productId: ProductId,
    val quantity: Quantity,
)

/** A line the catalogue could not reserve: requested and available quantities. */
data class StockShortage(
    val productId: ProductId,
    val requested: Int,
    val available: Int,
)

/** Pure decisions of the checkout (FR-010..FR-012), kept out of the use case so that property tests cover them. */
object Checkout {
    /**
     * The lines whose current price differs from the price the cart recorded when they were added; products the
     * catalogue no longer knows are left out. May be empty when only quantities or lines changed.
     */
    fun changedLines(
        cart: Cart,
        prices: List<ProductPrice>,
    ): List<ChangedLine> {
        val byProduct = prices.associateBy(ProductPrice::productId)
        return cart.lines.mapNotNull { line ->
            byProduct[line.productId]
                ?.price
                ?.takeIf { it != line.priceAtAdd }
                ?.let { ChangedLine(line.lineId, line.productId, line.priceAtAdd, it) }
        }
    }

    /**
     * The order lines frozen at the current catalogue prices and names (FR-011). Unknown and withdrawn products
     * cannot be sold: they refuse the checkout like a shortage, with nothing available.
     */
    fun freezeLines(
        cart: Cart,
        prices: List<ProductPrice>,
    ): Either<OrderError.InsufficientStock, List<OrderLine>> {
        val byProduct = prices.associateBy(ProductPrice::productId)
        val unsellable =
            cart.lines
                .filter { byProduct[it.productId]?.active != true }
                .map { UnavailableLine(it.productId, it.name, it.quantity.value, 0) }
        return if (unsellable.isEmpty()) {
            cart.lines
                .map { line ->
                    val price = byProduct.getValue(line.productId)
                    OrderLine(line.productId, price.sku, price.name, price.price, line.quantity)
                }.right()
        } else {
            OrderError.InsufficientStock(unsellable).left()
        }
    }

    /** The stock to reserve for [lines]. */
    fun stockLines(lines: List<OrderLine>): List<StockLine> = lines.map { StockLine(it.productId, it.quantity) }

    /** The refusal for the catalogue's [shortages], with the product names of the cart. */
    fun insufficientStock(
        cart: Cart,
        shortages: List<StockShortage>,
    ): OrderError.InsufficientStock {
        val names = cart.lines.associate { it.productId to it.name }
        return OrderError.InsufficientStock(
            shortages.map { UnavailableLine(it.productId, names[it.productId].orEmpty(), it.requested, it.available) },
        )
    }
}

/** What identifies the body of a checkout request: same fingerprint, same request (FR-013). */
data class CheckoutRequest(
    val addressId: AddressId,
    val cartRevision: String,
    val paymentMethodType: String,
    val paymentToken: String,
) {
    /** SHA-256 (hex) of the request fields, in a fixed order and separated so that fields cannot run together. */
    fun fingerprint(): String {
        val canonical = listOf(addressId.toString(), cartRevision, paymentMethodType, paymentToken).joinToString("\n")
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()))
    }
}
