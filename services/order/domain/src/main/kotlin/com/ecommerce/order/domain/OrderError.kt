package com.ecommerce.order.domain

/**
 * Expected failures of the order context, as values (Principle IV). The web layer maps each one to its RFC 9457
 * problem (`contracts/openapi/order.yaml`); nothing here knows about HTTP.
 */
sealed interface OrderError {
    /** A value broke its rule; [field] names the offending input. */
    data class Invalid(
        val field: String,
        val reason: String,
    ) : OrderError

    /** The caller lacks the role the operation requires. */
    data object Forbidden : OrderError

    /** No such order, or an order of another shopper (existence is not revealed). */
    data object OrderNotFound : OrderError

    /** The shopper has no cart or a cart without lines. */
    data object EmptyCart : OrderError

    /** The delivery address is unknown or belongs to another account. */
    data object AddressNotFound : OrderError

    /** The account has no contact details any more (unknown or anonymised). */
    data object AccountNotFound : OrderError

    /** The cart revision the shopper saw is not the current one (409 `price-changed`). */
    data class PriceChanged(
        val changedLines: List<ChangedLine>,
        val currentCartRevision: String,
    ) : OrderError

    /** Stock could not be reserved for [lines] (409 `insufficient-stock`). */
    data class InsufficientStock(
        val lines: List<UnavailableLine>,
    ) : OrderError

    /** The `Idempotency-Key` was already used with a different request (422 `idempotency-key-reuse`). */
    data object IdempotencyKeyReuse : OrderError

    /** An identical request with the same key is still being processed. */
    data object IdempotencyKeyInUse : OrderError

    /** Shoppers cancel only while the order is `placed` (409 `order-not-cancellable`). */
    data class NotCancellable(
        val orderStatus: OrderStatus,
    ) : OrderError

    /** The lifecycle refuses moving from [from] to [to] (409 `invalid-transition`); [reason] explains why. */
    data class InvalidTransition(
        val from: OrderStatus,
        val to: OrderStatus,
        val reason: String,
    ) : OrderError

    /** Another change of the same order won the optimistic lock; the caller may retry. */
    data object ConcurrentUpdate : OrderError
}

/** A cart line whose current catalogue price differs from the price the cart recorded. */
data class ChangedLine(
    val lineId: String,
    val productId: ProductId,
    val oldPrice: Money,
    val newPrice: Money,
)

/** A cart line that cannot be reserved: what was asked for and what is available now. */
data class UnavailableLine(
    val productId: ProductId,
    val name: String,
    val requestedQuantity: Int,
    val availableQuantity: Int,
)
