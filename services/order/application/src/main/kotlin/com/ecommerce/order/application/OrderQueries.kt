package com.ecommerce.order.application

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ecommerce.order.domain.Caller
import com.ecommerce.order.domain.Order
import com.ecommerce.order.domain.OrderError
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.OrderStatus
import com.ecommerce.order.domain.Page
import com.ecommerce.order.domain.PageRequest

/**
 * The caller's own orders, newest first (FR-016); every shopper's orders for an operator (the console list). Both can
 * narrow the page to one `orderStatus`. Callers with neither role are refused.
 */
class ListOwnOrders(
    private val orders: OrderRepository,
) {
    suspend operator fun invoke(
        caller: Caller,
        page: PageRequest,
        status: OrderStatus? = null,
    ): Either<OrderError, Page<Order>> =
        when {
            caller.isOperator -> orders.search(null, status, page).right()
            caller.isShopper -> orders.search(caller.accountId, status, page).right()
            else -> OrderError.Forbidden.left()
        }
}

/** One order: its owner sees it, operators see any; another shopper's order is "not found" (FR-016). */
class GetOwnOrder(
    private val orders: OrderRepository,
) {
    suspend operator fun invoke(
        caller: Caller,
        orderId: OrderId,
    ): Either<OrderError, Order> =
        if (caller.isShopper || caller.isOperator) {
            orders.findById(orderId)?.takeIf { it.isVisibleTo(caller) }?.right() ?: OrderError.OrderNotFound.left()
        } else {
            OrderError.Forbidden.left()
        }
}
