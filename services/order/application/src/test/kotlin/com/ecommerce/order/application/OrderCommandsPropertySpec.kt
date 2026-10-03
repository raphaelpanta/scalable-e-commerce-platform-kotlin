package com.ecommerce.order.application

import com.ecommerce.order.domain.Caller
import com.ecommerce.order.domain.CancellationReason
import com.ecommerce.order.domain.Order
import com.ecommerce.order.domain.OrderError
import com.ecommerce.order.domain.OrderEvent
import com.ecommerce.order.domain.OrderLifecycle
import com.ecommerce.order.domain.OrderStatus
import com.ecommerce.order.domain.PaymentStatus
import com.ecommerce.order.domain.Role
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.checkAll

/** Nothing was stored, published or released for [order]. */
private fun OrderBackend.untouched(order: Order) {
    current(order.id) shouldBe order
    events.published.shouldBeEmpty()
    catalog.released.shouldBeEmpty()
}

/** The stored state of a change that returned [after]: the next version of it. */
private fun OrderBackend.storedAs(after: Order) {
    current(after.id) shouldBe after.copy(version = after.version + 1)
}

/** The reservation is released exactly when the change cancelled an order whose payment was still pending. */
private fun OrderBackend.releasedWhenVoided(before: Order) {
    catalog.released shouldBe if (before.paymentStatus == PaymentStatus.PENDING) listOf(RESERVATION) else emptyList()
}

/** Constitution V: shopper cancellation (FR-016) and operator transitions (FR-017) over generated orders. */
class OrderCommandsPropertySpec :
    FunSpec({
        test("a shopper cancels their own order exactly while it is placed; any other attempt changes nothing") {
            checkAll(arbOrder(), arbCaller) { order, caller ->
                val backend = OrderBackend(listOf(order))

                val result = CancelOwnOrder(backend.store, backend.catalog, fixedClock())(caller, order.id)

                val refusal =
                    when {
                        !caller.isShopper -> OrderError.Forbidden
                        caller.accountId != order.accountId -> OrderError.OrderNotFound
                        order.orderStatus != OrderStatus.PLACED -> OrderError.NotCancellable(order.orderStatus)
                        else -> null
                    }
                if (refusal != null) {
                    result.leftOrNull() shouldBe refusal
                    backend.untouched(order)
                } else {
                    val cancelled = result.getOrNull().shouldNotBeNull()
                    cancelled.orderStatus shouldBe OrderStatus.CANCELLED
                    cancelled.cancellation?.reason shouldBe CancellationReason.SHOPPER_REQUEST
                    cancelled.cancellation?.by shouldBe order.accountId.toString()
                    cancelled.paymentStatus shouldBe
                        if (order.paymentStatus == PaymentStatus.PENDING) PaymentStatus.FAILED else order.paymentStatus
                    cancelled.paymentExpiresAt shouldBe null
                    cancelled.lines shouldBe order.lines
                    backend.storedAs(cancelled)
                    backend.events.published
                        .single()
                        .shouldBeInstanceOf<OrderEvent.Cancelled>()
                        .refundRequired shouldBe (order.paymentStatus == PaymentStatus.APPROVED)
                    backend.releasedWhenVoided(order)
                }
            }
        }

        test("an operator moves an order exactly along the lifecycle; refused moves change nothing") {
            checkAll(arbOrder(), arbCaller, Arb.enum<OrderStatus>()) { order, caller, target ->
                val backend = OrderBackend(listOf(order))

                val result =
                    TransitionOrderStatus(backend.store, backend.catalog, fixedClock())(caller, order.id, target)

                val refusal = OrderLifecycle.refusal(order.orderStatus, order.paymentStatus, target)
                when {
                    !caller.isOperator -> {
                        result.leftOrNull() shouldBe OrderError.Forbidden
                        backend.untouched(order)
                    }

                    refusal != null -> {
                        result.leftOrNull() shouldBe OrderError.InvalidTransition(order.orderStatus, target, refusal)
                        backend.untouched(order)
                    }

                    else -> {
                        val moved = result.getOrNull().shouldNotBeNull()
                        moved.orderStatus shouldBe target
                        moved.history.dropLast(1).take(order.history.size) shouldBe order.history
                        moved.history.last().by shouldBe caller.accountId.toString()
                        backend.storedAs(moved)
                        val event = backend.events.published.single()
                        if (target == OrderStatus.CANCELLED) {
                            moved.cancellation?.reason shouldBe CancellationReason.OPERATOR
                            event.shouldBeInstanceOf<OrderEvent.Cancelled>()
                            backend.releasedWhenVoided(order)
                        } else {
                            event.shouldBeInstanceOf<OrderEvent.StatusChanged>().changedBy shouldBe caller.accountId
                            backend.catalog.released.shouldBeEmpty()
                        }
                    }
                }
            }
        }

        test("an unknown order is not found for every command, and nothing is stored") {
            checkAll(arbOrder(), Arb.enum<OrderStatus>()) { order, target ->
                val backend = OrderBackend(emptyList())
                CancelOwnOrder(backend.store, backend.catalog, fixedClock())(
                    Caller(order.accountId, setOf(Role.SHOPPER)),
                    order.id,
                ).leftOrNull() shouldBe OrderError.OrderNotFound
                TransitionOrderStatus(backend.store, backend.catalog, fixedClock())(
                    Caller(OPERATOR, setOf(Role.OPERATOR)),
                    order.id,
                    target,
                ).leftOrNull() shouldBe OrderError.OrderNotFound
                backend.orders.stored.size shouldBe 0
                backend.events.published.shouldBeEmpty()
            }
        }
    })
