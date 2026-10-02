package com.ecommerce.order.domain

import com.ecommerce.order.domain.OrderFixtures.NOW
import com.ecommerce.order.domain.OrderFixtures.OPERATOR
import com.ecommerce.order.domain.OrderFixtures.SHOPPER
import com.ecommerce.order.domain.OrderFixtures.arbState
import com.ecommerce.order.domain.OrderFixtures.paid
import com.ecommerce.order.domain.OrderFixtures.paidThen
import com.ecommerce.order.domain.OrderFixtures.placed
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.checkAll

private val LEGAL_MOVES =
    setOf(
        OrderStatus.PLACED to OrderStatus.PREPARING,
        OrderStatus.PLACED to OrderStatus.CANCELLED,
        OrderStatus.PREPARING to OrderStatus.SHIPPED,
        OrderStatus.PREPARING to OrderStatus.CANCELLED,
        OrderStatus.SHIPPED to OrderStatus.DELIVERED,
    )

class OrderTransitionsSpec :
    FunSpec({
        test("an operator move is legal exactly for the lifecycle table") {
            checkAll(Arb.enum<OrderStatus>(), Arb.enum<OrderStatus>()) { from, to ->
                OrderLifecycle.isOperatorMove(from, to) shouldBe ((from to to) in LEGAL_MOVES)
            }
        }

        test("an illegal move or preparing without an approved payment is refused with a reason") {
            checkAll(arbState, Arb.enum<OrderStatus>()) { order, target ->
                val legal =
                    (order.orderStatus to target) in LEGAL_MOVES &&
                        (target != OrderStatus.PREPARING || order.paymentStatus == PaymentStatus.APPROVED)
                val result = order.transition(target, OPERATOR, NOW)
                if (legal) {
                    result.isRight() shouldBe true
                    OrderLifecycle.refusal(order.orderStatus, order.paymentStatus, target) shouldBe null
                } else {
                    val reason = checkNotNull(OrderLifecycle.refusal(order.orderStatus, order.paymentStatus, target))
                    result.leftOrNull() shouldBe OrderError.InvalidTransition(order.orderStatus, target, reason)
                }
            }
        }

        test("the refusal names the statuses or the payment guard") {
            OrderLifecycle.refusal(OrderStatus.DELIVERED, PaymentStatus.APPROVED, OrderStatus.PREPARING) shouldBe
                "Cannot move an order from delivered to preparing."
            OrderLifecycle.refusal(OrderStatus.PLACED, PaymentStatus.PENDING, OrderStatus.PREPARING) shouldBe
                "An order moves to preparing only while its payment is approved; the payment is pending."
            OrderLifecycle.refusal(OrderStatus.PLACED, PaymentStatus.PENDING, OrderStatus.CANCELLED) shouldBe null
        }

        test("an accepted move changes the order status, records the operator and publishes the matching event") {
            listOf(
                paid() to OrderStatus.PREPARING,
                paidThen(OrderStatus.PREPARING) to OrderStatus.SHIPPED,
                paidThen(OrderStatus.PREPARING, OrderStatus.SHIPPED) to OrderStatus.DELIVERED,
            ).forEach { (order, target) ->
                val change = checkNotNull(order.transition(target, OPERATOR, NOW.plusSeconds(60)).getOrNull())
                change.order.orderStatus shouldBe target
                change.order.paymentStatus shouldBe PaymentStatus.APPROVED
                change.order.history shouldBe
                    order.history +
                    StatusChange(
                        StatusKind.ORDER,
                        order.orderStatus.wire,
                        target.wire,
                        NOW.plusSeconds(60),
                        OPERATOR.toString(),
                    )
                change.events shouldContainExactly
                    listOf(OrderEvent.StatusChanged(change.order, NOW.plusSeconds(60), OPERATOR))
            }
        }

        test("an operator cancels placed or preparing orders with reason OPERATOR") {
            listOf(placed(), paid(), paidThen(OrderStatus.PREPARING)).forEach { order ->
                val change = checkNotNull(order.transition(OrderStatus.CANCELLED, OPERATOR, NOW).getOrNull())
                change.order.orderStatus shouldBe OrderStatus.CANCELLED
                change.order.cancellation shouldBe Cancellation(CancellationReason.OPERATOR, NOW, OPERATOR.toString())
                change.order.history.last() shouldBe
                    StatusChange(
                        StatusKind.ORDER,
                        order.orderStatus.wire,
                        "cancelled",
                        NOW,
                        OPERATOR.toString(),
                        CancellationReason.OPERATOR,
                    )
                change.events
                    .single()
                    .shouldBeInstanceOf<OrderEvent.Cancelled>()
                    .order shouldBe change.order
            }
        }

        test("a shopper cancels only while the order is placed") {
            checkAll(arbState) { order ->
                val result = order.cancelByShopper(NOW)
                if (order.orderStatus == OrderStatus.PLACED) {
                    val cancelled = checkNotNull(result.getOrNull()).order
                    cancelled.cancellation shouldBe
                        Cancellation(CancellationReason.SHOPPER_REQUEST, NOW, SHOPPER.toString())
                } else {
                    result.leftOrNull() shouldBe OrderError.NotCancellable(order.orderStatus)
                }
            }
        }

        test("cancelling before payment voids it; cancelling after payment keeps it approved for the refund") {
            val pending = checkNotNull(placed().cancelByShopper(NOW).getOrNull())
            placed().holdsUncommittedReservation shouldBe true
            pending.order.paymentStatus shouldBe PaymentStatus.FAILED
            pending.order.paymentExpiresAt shouldBe null
            pending.order.history.takeLast(2) shouldContainExactly
                listOf(
                    StatusChange(StatusKind.PAYMENT, "pending", "failed", NOW, "system"),
                    StatusChange(
                        StatusKind.ORDER,
                        "placed",
                        "cancelled",
                        NOW,
                        SHOPPER.toString(),
                        CancellationReason.SHOPPER_REQUEST,
                    ),
                )
            pending.events
                .single()
                .shouldBeInstanceOf<OrderEvent.Cancelled>()
                .refundRequired shouldBe false

            val approved = checkNotNull(paid().cancelByShopper(NOW).getOrNull())
            paid().holdsUncommittedReservation shouldBe false
            approved.order.paymentStatus shouldBe PaymentStatus.APPROVED
            approved.order.history.size shouldBe paid().history.size + 1
            approved.events
                .single()
                .shouldBeInstanceOf<OrderEvent.Cancelled>()
                .refundRequired shouldBe true
        }

        test("a refused transition leaves the order exactly as it was") {
            checkAll(arbState, Arb.enum<OrderStatus>()) { order, target ->
                val before = order.copy()
                order.transition(target, OPERATOR, NOW)
                order.cancelByShopper(NOW)
                order shouldBe before
            }
        }
    })
