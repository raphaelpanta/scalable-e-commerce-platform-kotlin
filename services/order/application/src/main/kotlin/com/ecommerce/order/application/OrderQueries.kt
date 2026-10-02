package com.ecommerce.order.application

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ecommerce.order.domain.Caller
import com.ecommerce.order.domain.Order
import com.ecommerce.order.domain.OrderError
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.Page
import com.ecommerce.order.domain.PageRequest

/** The caller's own orders, newest first (FR-016); shoppers only. */
class ListOwnOrders(
    private val orders: OrderRepository,
) {
    suspend operator fun invoke(
        caller: Caller,
        page: PageRequest,
    ): Either<OrderError, Page<Order>> =
        if (caller.isShopper) orders.findByAccount(caller.accountId, page).right() else OrderError.Forbidden.left()
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
