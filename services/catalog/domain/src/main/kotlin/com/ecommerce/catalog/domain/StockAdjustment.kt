package com.ecommerce.catalog.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import java.time.Instant

/**
 * An attributed stock change (FR-002, data-model section 3.2, append-only): [delta] units added (positive) or
 * removed (negative) on hand of [productId] because of [reason], by the operator [actorId] [at] a time, with the
 * available quantity before and after.
 */
data class StockAdjustment(
    val id: AdjustmentId,
    val productId: ProductId,
    val delta: Int,
    val reason: StockAdjustmentReason,
    val actorId: AccountId,
    val at: Instant,
    val previousAvailable: Int,
    val newAvailable: Int,
) {
    init {
        require(delta != 0) { "an adjustment changes stock" }
        require(newAvailable - previousAvailable == delta) { "the available quantity moves by the delta" }
        require(newAvailable >= 0) { "the available quantity never becomes negative" }
    }

    /** The level an adjustment produced and its record. */
    data class Applied(
        val level: InventoryLevel,
        val adjustment: StockAdjustment,
    )

    companion object {
        /** Largest change of one adjustment, in either direction. */
        const val MAX_DELTA: Int = StockLevel.MAX

        /** A signed, non-zero delta of at most [MAX_DELTA] units. */
        fun delta(raw: Int): Either<FieldIssue, Int> =
            when {
                raw == 0 -> FieldIssue("delta", "must not be 0").left()
                raw !in -MAX_DELTA..MAX_DELTA -> FieldIssue("delta", "must be at most $MAX_DELTA either way").left()
                else -> raw.right()
            }

        /** Applies [delta] to [level]; refused when the available quantity would become negative. */
        @Suppress("LongParameterList") // the audit fields of data-model section 3.2
        fun apply(
            id: AdjustmentId,
            level: InventoryLevel,
            delta: Int,
            reason: StockAdjustmentReason,
            actorId: AccountId,
            at: Instant,
        ): Either<CatalogError, Applied> =
            either {
                val adjusted = level.adjust(delta).bind()
                val (before, after) = level.available to adjusted.available
                Applied(adjusted, StockAdjustment(id, level.productId, delta, reason, actorId, at, before, after))
            }
    }
}
