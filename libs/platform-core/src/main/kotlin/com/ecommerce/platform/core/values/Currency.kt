package com.ecommerce.platform.core.values

import arrow.core.left
import arrow.core.right
import com.ecommerce.platform.core.result.Validated
import com.ecommerce.platform.core.result.ValidationError

/** An ISO-4217 currency code known to the JDK (`BRL`, `EUR`, ...). The MVP runs single-currency on [BRL]. */
@JvmInline
value class Currency private constructor(
    val code: String,
) {
    override fun toString(): String = code

    companion object {
        private val CODE = Regex("[A-Z]{3}")
        private const val MALFORMED = "must be an ISO-4217 code of three upper-case letters"
        private val KNOWN: Set<String> =
            java.util.Currency
                .getAvailableCurrencies()
                .map { it.currencyCode }
                .toSet()

        /** The platform currency (`PLATFORM_CURRENCY`, data-model section 1). */
        val BRL: Currency = Currency("BRL")

        /** Accepts three upper-case letters naming a currency the JDK knows. */
        fun of(
            code: String,
            field: String = "currency",
        ): Validated<Currency> =
            when {
                !CODE.matches(code) -> ValidationError(field, MALFORMED).left()
                code !in KNOWN -> ValidationError(field, "is not a known ISO-4217 currency").left()
                else -> Currency(code).right()
            }
    }
}
