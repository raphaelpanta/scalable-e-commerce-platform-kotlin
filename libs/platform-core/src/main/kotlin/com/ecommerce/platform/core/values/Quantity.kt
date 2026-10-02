package com.ecommerce.platform.core.values

import arrow.core.left
import arrow.core.right
import com.ecommerce.platform.core.result.Validated
import com.ecommerce.platform.core.result.ValidationError

/**
 * The quantity of one cart or order line, 1..99 (data-model section 2). Zero is not a quantity: "set to 0" is
 * expressed as removing the line.
 */
@JvmInline
value class Quantity private constructor(
    val value: Int,
) {
    /** The sum of two quantities, rejected above [MAX]. */
    operator fun plus(other: Quantity): Validated<Quantity> = of(value + other.value)

    override fun toString(): String = value.toString()

    companion object {
        const val MIN: Int = 1
        const val MAX: Int = 99

        /** Accepts [MIN]..[MAX]. */
        fun of(
            value: Int,
            field: String = "quantity",
        ): Validated<Quantity> =
            when {
                value < MIN -> ValidationError(field, "must be at least $MIN").left()
                value > MAX -> ValidationError(field, "must be at most $MAX").left()
                else -> Quantity(value).right()
            }
    }
}
