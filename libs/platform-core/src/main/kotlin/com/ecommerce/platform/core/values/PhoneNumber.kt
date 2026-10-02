package com.ecommerce.platform.core.values

import arrow.core.left
import arrow.core.right
import com.ecommerce.platform.core.result.Validated
import com.ecommerce.platform.core.result.ValidationError
import com.ecommerce.platform.observability.Pii
import com.ecommerce.platform.observability.PiiMasking

/**
 * A phone number in E.164 form: `+`, a non-zero first digit and 8 to 15 digits in total (data-model section 2).
 * Surrounding whitespace is ignored; nothing else is normalised. Personal data: `toString()` shows only the last
 * two digits.
 */
@Pii
@JvmInline
value class PhoneNumber private constructor(
    val value: String,
) {
    override fun toString(): String = PiiMasking.maskAllButLast(value, VISIBLE_DIGITS)

    companion object {
        private const val VISIBLE_DIGITS = 2
        private val E164 = Regex("\\+[1-9][0-9]{7,14}")

        /** Validates [raw] as E.164. */
        fun of(
            raw: String,
            field: String = "phoneNumber",
        ): Validated<PhoneNumber> {
            val value = raw.trim()
            return if (E164.matches(value)) {
                PhoneNumber(value).right()
            } else {
                ValidationError(field, "must be in E.164 format (+ and 8 to 15 digits)").left()
            }
        }
    }
}
