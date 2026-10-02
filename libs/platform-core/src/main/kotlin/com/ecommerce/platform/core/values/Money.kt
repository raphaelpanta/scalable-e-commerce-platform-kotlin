package com.ecommerce.platform.core.values

import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import com.ecommerce.platform.core.result.Validated
import com.ecommerce.platform.core.result.ValidationError

/**
 * An amount in minor units (`1999` BRL is R$ 19,99) of one [currency]. JSON shape `{"amountMinor": 1999,
 * "currency": "BRL"}` (service conventions section 3).
 *
 * Amounts are non-negative ([of]); only adjustment and refund records use [signed] or [difference]. Arithmetic is
 * defined between equal currencies only and every overflow of `Long` is rejected instead of wrapping around.
 */
@ConsistentCopyVisibility
data class Money private constructor(
    val amountMinor: Long,
    val currency: Currency,
) {
    /** True for an amount of zero. */
    val isZero: Boolean get() = amountMinor == 0L

    /** True for a negative (adjustment) amount, only possible through [signed] or [difference]. */
    val isNegative: Boolean get() = amountMinor < 0L

    /** The sum of two amounts of the same currency. */
    operator fun plus(other: Money): Validated<Money> =
        sameCurrency(other).flatMap { exact { Math.addExact(amountMinor, other.amountMinor) } }

    /** The difference of two amounts of the same currency; a negative result is rejected (use [difference]). */
    operator fun minus(other: Money): Validated<Money> =
        difference(other).flatMap { result ->
            if (result.isNegative) {
                ValidationError(AMOUNT, "must not become negative").left()
            } else {
                result.right()
            }
        }

    /** The signed difference `this - other`, for adjustment records; same currency, no overflow. */
    fun difference(other: Money): Validated<Money> =
        sameCurrency(other).flatMap { exact { Math.subtractExact(amountMinor, other.amountMinor) } }

    /** This amount multiplied by a non-negative [factor] (a line total is `unitPrice * quantity`). */
    operator fun times(factor: Int): Validated<Money> =
        if (factor < 0) {
            ValidationError("factor", "must not be negative").left()
        } else {
            exact { Math.multiplyExact(amountMinor, factor.toLong()) }
        }

    /** This amount multiplied by a [Quantity]. */
    operator fun times(quantity: Quantity): Validated<Money> = times(quantity.value)

    private fun sameCurrency(other: Money): Validated<Unit> =
        if (currency == other.currency) {
            Unit.right()
        } else {
            ValidationError(CURRENCY, "must be $currency, was ${other.currency}").left()
        }

    private fun exact(operation: () -> Long): Validated<Money> =
        try {
            Money(operation(), currency).right()
        } catch (_: ArithmeticException) {
            ValidationError(AMOUNT, "is out of range").left()
        }

    companion object {
        private const val AMOUNT = "amountMinor"
        private const val CURRENCY = "currency"

        /** A non-negative amount. */
        fun of(
            amountMinor: Long,
            currency: Currency,
        ): Validated<Money> =
            if (amountMinor < 0L) {
                ValidationError(AMOUNT, "must not be negative").left()
            } else {
                Money(amountMinor, currency).right()
            }

        /** A non-negative amount of the currency named by [currencyCode]. */
        fun of(
            amountMinor: Long,
            currencyCode: String,
        ): Validated<Money> = Currency.of(currencyCode).flatMap { of(amountMinor, it) }

        /** An amount that may be negative, for adjustment and refund records only (data-model section 1). */
        fun signed(
            amountMinor: Long,
            currency: Currency,
        ): Money = Money(amountMinor, currency)

        /** Zero in [currency]. */
        fun zero(currency: Currency): Money = Money(0L, currency)

        /** The sum of [amounts], all in [currency]; zero when empty. */
        fun sum(
            amounts: Iterable<Money>,
            currency: Currency,
        ): Validated<Money> =
            amounts.fold<Money, Validated<Money>>(zero(currency).right()) { total, next ->
                total.flatMap { it + next }
            }
    }
}
