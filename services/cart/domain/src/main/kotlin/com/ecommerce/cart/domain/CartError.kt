package com.ecommerce.cart.domain

/** Why a cart operation was refused; the web layer maps each case to its RFC 9457 problem (cart.yaml). */
sealed interface CartError {
    /** A quantity outside 1..99. */
    data class InvalidQuantity(
        val requested: Int,
    ) : CartError

    /** The resulting quantity of [productId] would exceed the [available] stock. */
    data class InsufficientStock(
        val productId: ProductId,
        val requested: Int,
        val available: Int,
    ) : CartError

    /** The product is withdrawn from sale. */
    data class ProductUnavailable(
        val productId: ProductId,
    ) : CartError

    /** The catalogue does not know the product. */
    data class ProductNotFound(
        val productId: ProductId,
    ) : CartError

    /** The cart has no line [lineId]. */
    data class LineNotFound(
        val lineId: LineId,
    ) : CartError

    /** A cart holds at most [Cart.MAX_LINES] lines. */
    data object TooManyLines : CartError

    /** The anonymous cart token is unknown, expired or already merged. */
    data object CartNotFound : CartError

    /** The cart changed (or was merged) concurrently; the caller may retry. */
    data object ConcurrentUpdate : CartError
}
