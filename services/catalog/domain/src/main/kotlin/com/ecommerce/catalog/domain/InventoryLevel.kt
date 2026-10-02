package com.ecommerce.catalog.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.nonEmptyListOf
import arrow.core.right

/**
 * How a reservation transition moves stock (data-model section 3.2): reserve `reserved += q`; commit
 * `onHand -= q, reserved -= q`; release `reserved -= q`; restock after the cancellation of a committed reservation
 * `onHand += q`.
 */
enum class StockEffect(
    private val onHandSign: Int,
    private val reservedSign: Int,
) {
    RESERVE(0, 1),
    COMMIT(-1, -1),
    RELEASE(0, -1),
    RESTOCK(1, 0),
    ;

    /** Change of `onHand` for [quantity] units. */
    fun onHandDelta(quantity: Int): Int = onHandSign * quantity

    /** Change of `reserved` for [quantity] units. */
    fun reservedDelta(quantity: Int): Int = reservedSign * quantity
}

/** A line that cannot be reserved: [requested] units of [productId] while only [available] can be. */
data class UnavailableLine(
    val productId: ProductId,
    val requested: Int,
    val available: Int,
)

/**
 * The stock of one product (data-model section 3.2): units [onHand] and units [reserved] for open orders, with
 * `0 <= reserved <= onHand`, so the [available] quantity `onHand - reserved` is never negative.
 */
data class InventoryLevel(
    val productId: ProductId,
    val onHand: Int,
    val reserved: Int,
    val version: Long = 0,
) {
    init {
        require(reserved >= 0) { "reserved must not be negative, was $reserved" }
        require(reserved <= onHand) { "reserved ($reserved) must not exceed onHand ($onHand)" }
    }

    val available: Int get() = onHand - reserved

    /** Holds [quantity] units, or names the shortage when fewer are available. */
    fun reserve(quantity: Quantity): Either<UnavailableLine, InventoryLevel> =
        if (available >= quantity.value) {
            apply(StockEffect.RESERVE, quantity).right()
        } else {
            UnavailableLine(productId, quantity.value, available).left()
        }

    /** The level after [effect] for [quantity] units; an effect that breaks the invariants fails fast. */
    fun apply(
        effect: StockEffect,
        quantity: Quantity,
    ): InventoryLevel =
        copy(
            onHand = Math.addExact(onHand, effect.onHandDelta(quantity.value)),
            reserved = reserved + effect.reservedDelta(quantity.value),
            version = version + 1,
        )

    /** Adds [delta] units on hand (negative removes); refused when the available quantity would become negative. */
    fun adjust(delta: Int): Either<CatalogError, InventoryLevel> {
        val onHandAfter = onHand.toLong() + delta
        return when {
            onHandAfter < reserved -> CatalogError.StockBelowReserved(productId, delta, available).left()
            onHandAfter > StockLevel.MAX -> CatalogError.Invalid(tooMuchStock()).left()
            else -> copy(onHand = onHandAfter.toInt(), version = version + 1).right()
        }
    }

    private fun tooMuchStock() = nonEmptyListOf(FieldIssue("delta", "stock must stay at most ${StockLevel.MAX}"))
}
