package com.ecommerce.order.application

import arrow.core.Either
import arrow.core.left
import com.ecommerce.order.domain.Caller
import com.ecommerce.order.domain.Order
import com.ecommerce.order.domain.OrderError
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.OrderStatus
import com.ecommerce.order.domain.cancelByShopper
import com.ecommerce.order.domain.holdsUncommittedReservation
import com.ecommerce.order.domain.transition
import java.time.Clock

/**
 * Releases the reservation of an order that was cancelled before its payment was approved; after an approval the
 * reservation is committed and catalog restocks from `OrderCancelled` instead.
 */
internal suspend fun CatalogPort.releaseIfCancelledBeforePayment(modification: Modification) {
    val before = modification.before
    val cancelled = modification.changed && modification.after.orderStatus == OrderStatus.CANCELLED
    if (cancelled && before.holdsUncommittedReservation) release(before.reservationId)
}

/**
 * A shopper cancels one of their orders while it is `placed` (FR-016): reason `SHOPPER_REQUEST`, `OrderCancelled`
 * published (payment refunds an approved charge), stock released when the payment was still pending.
 */
class CancelOwnOrder(
    private val store: OrderStore,
    private val catalog: CatalogPort,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        caller: Caller,
        orderId: OrderId,
    ): Either<OrderError, Order> {
        if (!caller.isShopper) return OrderError.Forbidden.left()
        return store
            .modify(orderId) { order ->
                if (order.accountId == caller.accountId) {
                    order.cancelByShopper(clock.instant())
                } else {
                    OrderError.OrderNotFound.left()
                }
            }.onRight { catalog.releaseIfCancelledBeforePayment(it) }
            .map { it.after }
    }
}

/**
 * An operator advances an order through the lifecycle or cancels it (FR-017); illegal transitions are refused and
 * leave the order unchanged.
 */
class TransitionOrderStatus(
    private val store: OrderStore,
    private val catalog: CatalogPort,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        caller: Caller,
        orderId: OrderId,
        target: OrderStatus,
    ): Either<OrderError, Order> {
        if (!caller.isOperator) return OrderError.Forbidden.left()
        return store
            .modify(orderId) { it.transition(target, caller.accountId, clock.instant()) }
            .onRight { catalog.releaseIfCancelledBeforePayment(it) }
            .map { it.after }
    }
}
