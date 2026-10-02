package com.ecommerce.cart.infrastructure.web

import arrow.core.nonEmptyListOf
import com.ecommerce.cart.domain.CartError
import com.ecommerce.cart.domain.Quantity
import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.core.result.ValidationError

private const val UNPROCESSABLE = 422

/**
 * The RFC 9457 problem of a refused cart operation (cart.yaml): 422 for quantities and products that cannot be sold
 * (a stock shortage as `insufficient-stock` with the available quantity in `detail` and `errors`, exactly as the
 * contract's example), 404 for unknown tokens, lines and products, 409 for a lost concurrent update.
 */
fun CartError.toProblem(): Problem =
    when (this) {
        is CartError.InvalidQuantity -> {
            Problem.validation(
                nonEmptyListOf(ValidationError("quantity", "must be between ${Quantity.MIN} and ${Quantity.MAX}")),
                "The quantity must be between ${Quantity.MIN} and ${Quantity.MAX}; requested $requested.",
            )
        }

        is CartError.InsufficientStock -> {
            Problem.of(
                ProblemType.INSUFFICIENT_STOCK,
                "Only $available units are available; requested $requested.",
                UNPROCESSABLE,
                mapOf(Problem.ERRORS to listOf(Problem.FieldError("quantity", "available quantity: $available"))),
            )
        }

        is CartError.ProductUnavailable -> {
            Problem.validation(
                nonEmptyListOf(ValidationError("productId", "is not available for sale")),
                "The product is not available for sale.",
            )
        }

        CartError.TooManyLines -> {
            Problem.validation(nonEmptyListOf(ValidationError("productId", "a cart holds at most 100 products")))
        }

        is CartError.ProductNotFound -> {
            Problem.notFound("Product not found.")
        }

        is CartError.LineNotFound -> {
            Problem.notFound("Cart line not found.")
        }

        CartError.CartNotFound -> {
            Problem.notFound("Cart token is unknown or expired.")
        }

        CartError.ConcurrentUpdate -> {
            Problem.conflict("The cart was changed by another request; read it again and retry.")
        }
    }
