package com.ecommerce.catalog.application.admin

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import com.ecommerce.catalog.application.Caller
import com.ecommerce.catalog.application.Catalog
import com.ecommerce.catalog.domain.AdjustmentId
import com.ecommerce.catalog.domain.CatalogError
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.catalog.domain.StockAdjustment
import com.ecommerce.catalog.domain.StockAdjustmentReason

/**
 * `adjustStock` (operator only, FR-002): adds or removes [delta] units on hand for a mandatory reason, attributed to
 * the operator and time-stamped. The domain rule refuses a delta that would make the available quantity negative;
 * the guarded update re-checks it against concurrent reservations, and the record carries the levels it produced.
 */
class AdjustStock(
    private val catalog: Catalog,
) {
    suspend operator fun invoke(
        caller: Caller,
        productId: ProductId,
        delta: Int,
        reason: String?,
    ): Either<CatalogError, StockAdjustment> =
        either {
            val actor = catalog.authorize(caller, ACTION, productId.value).bind()
            val (validDelta, validReason) =
                Either
                    .zipOrAccumulate(StockAdjustment.delta(delta), StockAdjustmentReason.of(reason)) { d, r -> d to r }
                    .mapLeft(CatalogError::Invalid)
                    .bind()
            ensureNotNull(catalog.products.find(productId)) { CatalogError.ProductNotFound(productId) }
            val adjustment =
                catalog.transactions
                    .inTransaction {
                        either {
                            val level =
                                ensureNotNull(catalog.inventory.find(productId)) {
                                    CatalogError.ProductNotFound(productId)
                                }
                            val id = AdjustmentId(catalog.newId())
                            val planned =
                                StockAdjustment.apply(id, level, validDelta, validReason, actor, catalog.now()).bind()
                            val after =
                                ensureNotNull(catalog.inventory.adjust(productId, validDelta)) {
                                    CatalogError.StockBelowReserved(productId, validDelta, level.available)
                                }
                            val recorded =
                                planned.adjustment.copy(
                                    previousAvailable = after.available - validDelta,
                                    newAvailable = after.available,
                                )
                            catalog.adjustments.record(recorded)
                            recorded
                        }
                    }.bind()
            catalog.audit.changed(actor, ACTION, productId.value)
            adjustment
        }

    private companion object {
        const val ACTION = "adjustStock"
    }
}
