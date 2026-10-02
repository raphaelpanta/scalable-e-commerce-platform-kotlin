package com.ecommerce.platform.core.values

import arrow.core.left
import arrow.core.right
import com.ecommerce.platform.core.result.Validated
import com.ecommerce.platform.core.result.ValidationError

/**
 * Length rules for text values (shared value objects here; `Sku`, `ProductName`, `StockAdjustmentReason` and the like
 * in the services); reasons are client-safe and name the limit.
 */
object TextRules {
    /** The trimmed [raw] when its length is within [min]..[max], otherwise the broken limit. */
    fun trimmedLength(
        raw: String,
        field: String,
        min: Int,
        max: Int,
    ): Validated<String> {
        val value = raw.trim()
        val tooShort = if (min == 1) BLANK else "must be at least $min characters"
        return when {
            value.length < min -> ValidationError(field, tooShort).left()
            value.length > max -> ValidationError(field, "must be at most $max characters").left()
            else -> value.right()
        }
    }

    /** Like [trimmedLength] with a minimum of zero, mapping a blank or absent value to `null`. */
    fun optionalTrimmedLength(
        raw: String?,
        field: String,
        max: Int,
    ): Validated<String?> =
        if (raw.isNullOrBlank()) {
            null.right()
        } else {
            trimmedLength(raw, field, 1, max)
        }

    /** True when every character is printable ASCII without space (`!`..`~`). */
    fun isVisibleAscii(value: String): Boolean = value.all { it in FIRST_VISIBLE..LAST_VISIBLE }

    const val BLANK: String = "must not be blank"
    private const val FIRST_VISIBLE = '!'
    private const val LAST_VISIBLE = '~'
}
