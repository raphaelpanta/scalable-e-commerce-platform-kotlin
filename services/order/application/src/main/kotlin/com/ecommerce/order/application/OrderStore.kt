package com.ecommerce.order.application

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ecommerce.order.domain.Order
import com.ecommerce.order.domain.OrderChange
import com.ecommerce.order.domain.OrderError
import com.ecommerce.order.domain.OrderId

/** A stored change: the order before and after it (equal when the change was a no-op). */
data class Modification(
    val before: Order,
    val after: Order,
) {
    val changed: Boolean get() = before != after
}

/**
 * Writes orders and their events together: every change runs in one transaction that stores the aggregate and
 * publishes its events through the outbox (no dual write). A change that lost the optimistic lock to a concurrent
 * one (a payment event racing the synchronous charge, for example) is re-applied to the fresh state.
 */
class OrderStore(
    val orders: OrderRepository,
    private val events: OrderEventPublisher,
    private val transactions: Transactions,
) {
    /** Stores a new order with its `OrderPlaced` event; [alongside] runs in the same transaction. */
    suspend fun create(
        change: OrderChange,
        alongside: suspend () -> Unit = {},
    ) {
        transactions.run {
            orders.insert(change.order)
            events.publish(change.events)
            alongside()
        }
    }

    /**
     * Applies [change] to the current state of order [id] and stores the result with its events; an unchanged order
     * is not written. `OrderNotFound` when the order does not exist, `ConcurrentUpdate` when the lock was lost
     * [MAX_ATTEMPTS] times in a row.
     */
    suspend fun modify(
        id: OrderId,
        change: (Order) -> Either<OrderError, OrderChange>,
    ): Either<OrderError, Modification> {
        repeat(MAX_ATTEMPTS) {
            val attempt = transactions.run { attempt(id, change) }
            if (attempt != null) return attempt
        }
        return OrderError.ConcurrentUpdate.left()
    }

    /** One try: the result, or null when another change won the lock. */
    private suspend fun attempt(
        id: OrderId,
        change: (Order) -> Either<OrderError, OrderChange>,
    ): Either<OrderError, Modification>? {
        val current = orders.findById(id) ?: return OrderError.OrderNotFound.left()
        return when (val result = change(current)) {
            is Either.Left -> {
                result
            }

            is Either.Right -> {
                val next = result.value
                when {
                    next.order == current -> {
                        Modification(current, current).right()
                    }

                    orders.update(next.order) -> {
                        events.publish(next.events)
                        Modification(current, next.order).right()
                    }

                    else -> {
                        null
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
