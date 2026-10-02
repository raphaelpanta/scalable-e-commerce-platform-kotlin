package com.ecommerce.cart.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/** Units of one product on a cart line: 1..99 (data-model section 2). Zero is not a quantity but a line removal. */
@JvmInline
value class Quantity private constructor(
    val value: Int,
) {
    companion object {
        const val MIN: Int = 1
        const val MAX: Int = 99

        /** [value] as a quantity, or [CartError.InvalidQuantity] outside 1..99. */
        fun of(value: Int): Either<CartError.InvalidQuantity, Quantity> =
            if (value in MIN..MAX) Quantity(value).right() else CartError.InvalidQuantity(value).left()
    }
}
