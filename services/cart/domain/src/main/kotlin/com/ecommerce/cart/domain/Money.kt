package com.ecommerce.cart.domain

/**
 * An amount in minor units of one ISO-4217 [currency] (data-model sections 1 and 2): never negative, arithmetic only
 * between equal currencies, overflow rejected. The cart's own copy of the value object (Principle VI). Amounts come
 * from the catalogue or from stored lines, so a broken invariant is a programming error and fails fast.
 */
data class Money(
    val amountMinor: Long,
    val currency: String,
) {
    init {
        require(amountMinor >= 0) { "amountMinor must not be negative, was $amountMinor" }
        require(CURRENCY.matches(currency)) { "currency must be an ISO-4217 code, was $currency" }
    }

    /** The sum of two amounts of the same currency. */
    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "cannot add $currency and ${other.currency}" }
        return Money(Math.addExact(amountMinor, other.amountMinor), currency)
    }

    /** This unit price for [quantity] units (a line total). */
    operator fun times(quantity: Quantity): Money =
        Money(Math.multiplyExact(amountMinor, quantity.value.toLong()), currency)

    companion object {
        private val CURRENCY = Regex("[A-Z]{3}")

        /** Zero in [currency]. */
        fun zero(currency: String): Money = Money(0, currency)

        /** The sum of [amounts], all in [currency]; zero when empty. */
        fun sum(
            amounts: Iterable<Money>,
            currency: String,
        ): Money = amounts.fold(zero(currency)) { total, amount -> total + amount }
    }
}
