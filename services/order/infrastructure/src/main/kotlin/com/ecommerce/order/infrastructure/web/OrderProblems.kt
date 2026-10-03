package com.ecommerce.order.infrastructure.web

import arrow.core.nonEmptyListOf
import com.ecommerce.order.domain.OrderError
import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.core.result.ValidationError

/** The RFC 9457 problems of order.yaml for each [OrderError] (service conventions section 3). */
object OrderProblems {
    private const val CONFLICT = 409
    private const val UNPROCESSABLE = 422

    /** `payment-declined` (422) with the decline category and the cancelled order. */
    fun paymentDeclined(extensions: Map<String, Any?>): Problem =
        Problem.custom(
            "payment-declined",
            "Payment declined",
            UNPROCESSABLE,
            "The payment provider declined the charge.",
            extensions,
        )

    /**
     * `order-cancelled` (409): a cancellation won the race against the charge; the members name the order and its
     * cancellation reason.
     */
    fun orderCancelled(extensions: Map<String, Any?>): Problem =
        Problem.custom(
            "order-cancelled",
            "Order cancelled",
            CONFLICT,
            "The order was cancelled while its payment was being processed; the payment no longer applies.",
            extensions,
        )

    /** The problem answering [error]. */
    fun of(error: OrderError): Problem =
        when (error) {
            is OrderError.Invalid -> invalid(error.field, error.reason)
            is OrderError.PriceChanged -> priceChanged(error)
            is OrderError.InsufficientStock -> insufficientStock(error)
            is OrderError.NotCancellable -> notCancellable(error)
            is OrderError.InvalidTransition -> invalidTransition(error)
            else -> fixed(error)
        }

    private fun fixed(error: OrderError): Problem =
        when (error) {
            OrderError.Forbidden -> {
                Problem.forbidden("This operation is not allowed for your role.")
            }

            OrderError.OrderNotFound -> {
                Problem.notFound("Order not found.")
            }

            OrderError.EmptyCart -> {
                invalid("cart", "is empty", "The cart is empty.")
            }

            OrderError.AddressNotFound -> {
                invalid("addressId", "is not one of your addresses")
            }

            OrderError.AccountNotFound -> {
                invalid("account", "has no contact details")
            }

            OrderError.IdempotencyKeyReuse -> {
                idempotencyKeyReuse()
            }

            OrderError.IdempotencyKeyInUse -> {
                Problem.conflict("A request with this Idempotency-Key is still being processed; retry later.")
            }

            else -> {
                Problem.conflict("The order changed at the same time; retry.")
            }
        }

    private fun invalidTransition(error: OrderError.InvalidTransition): Problem =
        Problem.custom("invalid-transition", "Invalid status transition", CONFLICT, error.reason)

    /** A 422 `validation` problem with one field error. */
    fun invalid(
        field: String,
        reason: String,
        detail: String? = null,
    ): Problem = Problem.validation(nonEmptyListOf(ValidationError(field, reason)), detail)

    private fun priceChanged(error: OrderError.PriceChanged): Problem =
        Problem.of(
            ProblemType.PRICE_CHANGED,
            "The price of one or more items changed since you last viewed the cart. Review the new prices and " +
                "resubmit with the current cart revision.",
            extensions =
                mapOf(
                    "changedLines" to
                        error.changedLines.map {
                            mapOf(
                                "lineId" to it.lineId,
                                "productId" to it.productId.toString(),
                                "oldPrice" to MoneyView(it.oldPrice.amountMinor, it.oldPrice.currency),
                                "newPrice" to MoneyView(it.newPrice.amountMinor, it.newPrice.currency),
                            )
                        },
                    "currentCartRevision" to error.currentCartRevision,
                ),
        )

    private fun insufficientStock(error: OrderError.InsufficientStock): Problem =
        Problem.of(
            ProblemType.INSUFFICIENT_STOCK,
            "One or more items in the cart are no longer available.",
            extensions =
                mapOf(
                    "unavailableLines" to
                        error.lines.map {
                            mapOf(
                                "productId" to it.productId.toString(),
                                "name" to it.name,
                                "requestedQuantity" to it.requestedQuantity,
                                "availableQuantity" to it.availableQuantity,
                            )
                        },
                ),
        )

    private fun idempotencyKeyReuse(): Problem =
        Problem.custom(
            "idempotency-key-reuse",
            "Idempotency key reused with a different request",
            UNPROCESSABLE,
            "The Idempotency-Key was already used with a different request body.",
            mapOf("idempotencyConflict" to true),
        )

    private fun notCancellable(error: OrderError.NotCancellable): Problem =
        Problem.custom(
            "order-not-cancellable",
            "Order cannot be cancelled",
            CONFLICT,
            "Shoppers can only cancel an order while it is placed; this order is ${error.orderStatus.wire}.",
        )
}
