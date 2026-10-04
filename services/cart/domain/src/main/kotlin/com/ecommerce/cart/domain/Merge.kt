package com.ecommerce.cart.domain

import java.time.Instant

/** A product whose merged quantity was capped: [requestedQuantity] summed, [appliedQuantity] kept (0: dropped). */
data class CappedLine(
    val productId: ProductId,
    val requestedQuantity: Int,
    val appliedQuantity: Int,
)

/** The account cart after a merge and every line that could not keep the summed quantity. */
data class MergeOutcome(
    val cart: Cart,
    val cappedLines: List<CappedLine>,
)

/**
 * Merges the lines of an [anonymous] cart into this (account) cart on sign-in (FR-009, data-model section 3.3): for
 * each product of the anonymous cart the quantities are summed and capped at `min(sum, sellable now)`, where a
 * withdrawn or unknown product (absent from [quotes]) is sellable 0 and a new product needs room in
 * [Cart.MAX_LINES]. A product capped to 0 leaves the cart; every capped product is reported. Products only in the
 * account cart are left as they are. New lines keep their anonymous snapshots (sku, name, price at add) under a fresh
 * id from [newLineId], but are stamped `addedAt = now`: they join the account cart at the merge, so a late
 * `OrderPaid` of an order placed before it ([Cart.removeOrdered] with an earlier `paidAt`) leaves them alone even when
 * the anonymous line is older than that order. A product already in the account cart keeps its `addedAt` and only
 * gets the summed quantity, as with [Cart.add]: a late `OrderPaid` then takes out just the units that order bought, so
 * the merged-in units stay, while a refreshed `addedAt` would wrongly protect the bought units too. The result is one
 * version more, so its revision changes.
 */
fun Cart.mergeFrom(
    anonymous: Cart,
    quotes: Map<ProductId, ProductQuote>,
    newLineId: () -> LineId,
    now: Instant,
): MergeOutcome {
    val merged = lines.toMutableList()
    val capped = mutableListOf<CappedLine>()
    anonymous.lines.forEach { incoming ->
        val index = merged.indexOfFirst { it.productId == incoming.productId }
        val existing = merged.getOrNull(index)
        val requested = (existing?.quantity?.value ?: 0) + incoming.quantity.value
        val room = existing != null || merged.size < Cart.MAX_LINES
        val applied = if (room) minOf(requested, quotes[incoming.productId]?.sellable() ?: 0) else 0
        if (applied < requested) capped += CappedLine(incoming.productId, requested, applied)
        val quantity = Quantity.of(applied).getOrNull()
        when {
            quantity == null -> if (existing != null) merged.removeAt(index)
            existing != null -> merged[index] = existing.copy(quantity = quantity)
            else -> merged += incoming.copy(id = newLineId(), quantity = quantity, addedAt = now)
        }
    }
    return MergeOutcome(changed(merged, now), capped)
}
