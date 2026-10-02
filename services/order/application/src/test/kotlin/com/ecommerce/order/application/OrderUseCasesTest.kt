package com.ecommerce.order.application

import arrow.core.left
import arrow.core.right
import com.ecommerce.order.domain.Caller
import com.ecommerce.order.domain.CancellationReason
import com.ecommerce.order.domain.Channel
import com.ecommerce.order.domain.DeclineCategory
import com.ecommerce.order.domain.IdempotencyKey
import com.ecommerce.order.domain.Money
import com.ecommerce.order.domain.Order
import com.ecommerce.order.domain.OrderError
import com.ecommerce.order.domain.OrderEvent
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.OrderLine
import com.ecommerce.order.domain.OrderNumber
import com.ecommerce.order.domain.OrderStatus
import com.ecommerce.order.domain.PageRequest
import com.ecommerce.order.domain.PaymentAttemptId
import com.ecommerce.order.domain.PaymentOutcome
import com.ecommerce.order.domain.PaymentStatus
import com.ecommerce.order.domain.Placement
import com.ecommerce.order.domain.ProductId
import com.ecommerce.order.domain.Quantity
import com.ecommerce.order.domain.Recipient
import com.ecommerce.order.domain.RefundId
import com.ecommerce.order.domain.Role
import com.ecommerce.order.domain.applyPayment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private val SHOPPER_CALLER = Caller(SHOPPER, setOf(Role.SHOPPER))
private val OTHER_CALLER = Caller(OTHER_SHOPPER, setOf(Role.SHOPPER))
private val OPERATOR_CALLER = Caller(OPERATOR, setOf(Role.OPERATOR))
private val NOBODY = Caller(SHOPPER, emptySet())
private val ATTEMPT = PaymentAttemptId(UUID.randomUUID())

private fun newOrder(
    owner: com.ecommerce.order.domain.AccountId = SHOPPER,
    placedAt: Instant = NOW,
): Order =
    checkNotNull(
        Order
            .place(
                Placement(
                    OrderId(UUID.randomUUID()),
                    OrderNumber.of(LocalDate.of(2026, 10, 2), 1),
                    owner,
                    listOf(OrderLine(ProductId(UUID.randomUUID()), "SKU-1", "Mug", Money(1000, "BRL"), Quantity(1))),
                    ADDRESS,
                    RECIPIENT,
                    "tok_sim_approve_4242",
                    IdempotencyKey(UUID.randomUUID()),
                    RESERVATION,
                    placedAt,
                ),
            ).getOrNull(),
    ).order

private class Backend {
    val orders = InMemoryOrders()
    val events = RecordingEvents()
    val transactions = DirectTransactions()
    val store = OrderStore(orders, events, transactions)
    val catalog = FakeCatalog()

    fun stored(order: Order): Order = order.also { orders.stored[it.id] = it }

    fun paid(owner: com.ecommerce.order.domain.AccountId = SHOPPER): Order =
        stored(newOrder(owner).applyPayment(PaymentOutcome.Approved(ATTEMPT), NOW).order)

    fun current(order: Order): Order = checkNotNull(orders.stored[order.id])
}

class OrderUseCasesTest :
    FunSpec({
        context("the order store") {
            test("a change is stored with a new version and its events in one transaction") {
                val backend = Backend()
                val order = backend.stored(newOrder())

                val modification =
                    backend.store
                        .modify(order.id) { it.applyPayment(PaymentOutcome.Approved(ATTEMPT), NOW).right() }
                        .getOrNull()

                modification?.changed shouldBe true
                modification?.before shouldBe order
                backend.current(order).version shouldBe 1
                backend.events.published
                    .single()
                    .shouldBeInstanceOf<OrderEvent.Paid>()
                backend.transactions.count shouldBe 1
            }

            test("an unchanged order is not written and publishes nothing") {
                val backend = Backend()
                val order = backend.stored(newOrder())
                backend.store
                    .modify(order.id) { it.unchanged().right() }
                    .getOrNull()
                    ?.changed shouldBe false
                backend.current(order).version shouldBe 0
                backend.events.published.shouldBeEmpty()
            }

            test("a refused change and an unknown order are errors") {
                val backend = Backend()
                val order = backend.stored(newOrder())
                backend.store.modify(order.id) { OrderError.Forbidden.left() }.leftOrNull() shouldBe
                    OrderError.Forbidden
                backend.store.modify(OrderId(UUID.randomUUID())) { it.unchanged().right() }.leftOrNull() shouldBe
                    OrderError.OrderNotFound
            }

            test("a lost lock is retried, three lost locks are a concurrent update") {
                val backend = Backend()
                val order = backend.stored(newOrder())
                backend.orders.lostUpdates = 2
                backend.store
                    .modify(order.id) { it.applyPayment(PaymentOutcome.Approved(ATTEMPT), NOW).right() }
                    .isRight() shouldBe true
                backend.transactions.count shouldBe 3

                val other = backend.stored(newOrder())
                backend.orders.lostUpdates = 3
                backend.store
                    .modify(other.id) { it.applyPayment(PaymentOutcome.Approved(ATTEMPT), NOW).right() }
                    .leftOrNull() shouldBe OrderError.ConcurrentUpdate
                backend.events.published.size shouldBe 1
            }

            test("a new order is inserted with its events") {
                val backend = Backend()
                val change =
                    Order.place(
                        Placement(
                            OrderId(UUID.randomUUID()),
                            OrderNumber("ORD-20261002-0001"),
                            SHOPPER,
                            newOrder().lines,
                            ADDRESS,
                            RECIPIENT,
                            "tok",
                            IdempotencyKey(UUID.randomUUID()),
                            RESERVATION,
                            NOW,
                        ),
                    )
                backend.store.create(checkNotNull(change.getOrNull()))
                backend.orders.stored.size shouldBe 1
                backend.events.published
                    .single()
                    .shouldBeInstanceOf<OrderEvent.Placed>()
            }
        }

        context("queries") {
            test("shoppers list their own orders newest first") {
                val backend = Backend()
                val older = backend.stored(newOrder(placedAt = NOW.minusSeconds(60)))
                val newer = backend.stored(newOrder())
                backend.stored(newOrder(OTHER_SHOPPER))

                val page = ListOwnOrders(backend.orders)(SHOPPER_CALLER, PageRequest(0, 20)).getOrNull()

                page?.items shouldBe listOf(newer, older)
                page?.totalItems shouldBe 2
                ListOwnOrders(backend.orders)(OPERATOR_CALLER, PageRequest(0, 20)).leftOrNull() shouldBe
                    OrderError.Forbidden
            }

            test("an order is visible to its owner and operators, not to other shoppers") {
                val backend = Backend()
                val order = backend.stored(newOrder())
                val get = GetOwnOrder(backend.orders)
                get(SHOPPER_CALLER, order.id).getOrNull() shouldBe order
                get(OPERATOR_CALLER, order.id).getOrNull() shouldBe order
                get(OTHER_CALLER, order.id).leftOrNull() shouldBe OrderError.OrderNotFound
                get(SHOPPER_CALLER, OrderId(UUID.randomUUID())).leftOrNull() shouldBe OrderError.OrderNotFound
                get(NOBODY, order.id).leftOrNull() shouldBe OrderError.Forbidden
            }
        }

        context("shopper cancellation") {
            test("a placed, unpaid order is cancelled and its reservation released") {
                val backend = Backend()
                val order = backend.stored(newOrder())

                val cancelled =
                    CancelOwnOrder(backend.store, backend.catalog, fixedClock())(SHOPPER_CALLER, order.id).getOrNull()

                cancelled?.cancellation?.reason shouldBe CancellationReason.SHOPPER_REQUEST
                cancelled?.paymentStatus shouldBe PaymentStatus.FAILED
                backend.catalog.released shouldContainExactly listOf(RESERVATION)
                backend.events.published
                    .single()
                    .shouldBeInstanceOf<OrderEvent.Cancelled>()
                    .refundRequired shouldBe
                    false
            }

            test("a paid order is cancelled without a release: catalog restocks from OrderCancelled") {
                val backend = Backend()
                val order = backend.paid()

                val cancelled = CancelOwnOrder(backend.store, backend.catalog, fixedClock())(SHOPPER_CALLER, order.id)

                cancelled.getOrNull()?.paymentStatus shouldBe PaymentStatus.APPROVED
                backend.catalog.released.shouldBeEmpty()
                backend.events.published
                    .single()
                    .shouldBeInstanceOf<OrderEvent.Cancelled>()
                    .refundRequired shouldBe
                    true
            }

            test(
                "other shoppers' orders are not found, operators use the status transition, late cancels are refused",
            ) {
                val backend = Backend()
                val order = backend.paid()
                val cancel = CancelOwnOrder(backend.store, backend.catalog, fixedClock())
                cancel(OTHER_CALLER, order.id).leftOrNull() shouldBe OrderError.OrderNotFound
                cancel(OPERATOR_CALLER, order.id).leftOrNull() shouldBe OrderError.Forbidden
                TransitionOrderStatus(backend.store, backend.catalog, fixedClock())(
                    OPERATOR_CALLER,
                    order.id,
                    OrderStatus.PREPARING,
                ).isRight() shouldBe true
                cancel(SHOPPER_CALLER, order.id).leftOrNull() shouldBe OrderError.NotCancellable(OrderStatus.PREPARING)
                backend.current(order).orderStatus shouldBe OrderStatus.PREPARING
            }
        }

        context("operator transitions") {
            test("an operator advances a paid order and records the change") {
                val backend = Backend()
                val order = backend.paid()
                val transition = TransitionOrderStatus(backend.store, backend.catalog, fixedClock())

                transition(OPERATOR_CALLER, order.id, OrderStatus.PREPARING).getOrNull()?.orderStatus shouldBe
                    OrderStatus.PREPARING
                transition(OPERATOR_CALLER, order.id, OrderStatus.SHIPPED)
                    .getOrNull()
                    ?.history
                    ?.last()
                    ?.by shouldBe
                    OPERATOR.toString()
                backend.events.published.map { (it as OrderEvent.StatusChanged).order.orderStatus } shouldBe
                    listOf(OrderStatus.PREPARING, OrderStatus.SHIPPED)
                backend.catalog.released.shouldBeEmpty()
            }

            test("an illegal transition is refused and leaves the order unchanged") {
                val backend = Backend()
                val order = backend.stored(newOrder())
                val transition = TransitionOrderStatus(backend.store, backend.catalog, fixedClock())

                transition(OPERATOR_CALLER, order.id, OrderStatus.PREPARING)
                    .leftOrNull()
                    .shouldBeInstanceOf<OrderError.InvalidTransition>()
                    .to shouldBe OrderStatus.PREPARING
                transition(SHOPPER_CALLER, order.id, OrderStatus.CANCELLED).leftOrNull() shouldBe OrderError.Forbidden
                backend.current(order) shouldBe order
                backend.events.published.shouldBeEmpty()
            }

            test("an operator cancellation of an unpaid order releases the reservation") {
                val backend = Backend()
                val order = backend.stored(newOrder())
                TransitionOrderStatus(backend.store, backend.catalog, fixedClock())(
                    OPERATOR_CALLER,
                    order.id,
                    OrderStatus.CANCELLED,
                ).getOrNull()?.cancellation?.reason shouldBe CancellationReason.OPERATOR
                backend.catalog.released shouldContainExactly listOf(RESERVATION)
            }
        }

        context("events and jobs") {
            test("payment outcomes are applied once, refunds recorded once") {
                val backend = Backend()
                val order = backend.stored(newOrder())
                val apply = ApplyPaymentOutcome(backend.store, fixedClock())

                apply(
                    order.id,
                    PaymentOutcome.Declined(ATTEMPT, DeclineCategory.INSUFFICIENT_FUNDS),
                ).getOrNull()?.changed shouldBe
                    true
                apply(order.id, PaymentOutcome.Approved(ATTEMPT)).getOrNull()?.changed shouldBe false
                apply(OrderId(UUID.randomUUID()), PaymentOutcome.Pending(null)).leftOrNull() shouldBe
                    OrderError.OrderNotFound
                backend.events.published
                    .single()
                    .shouldBeInstanceOf<OrderEvent.PaymentFailed>()

                val refund = RecordRefund(backend.store, fixedClock())
                val refundId = RefundId(UUID.randomUUID())
                refund(order.id, refundId)
                    .getOrNull()
                    ?.after
                    ?.refund
                    ?.refundId shouldBe refundId
                refund(order.id, RefundId(UUID.randomUUID())).getOrNull()?.changed shouldBe false
            }

            test("an account deletion anonymises every order of the account once") {
                val backend = Backend()
                val first = backend.stored(newOrder())
                backend.stored(newOrder())
                val other = backend.stored(newOrder(OTHER_SHOPPER))
                val anonymise = AnonymiseAccountOrders(backend.store)

                anonymise(SHOPPER, "anon-1").getOrNull() shouldBe 2
                anonymise(SHOPPER, "anon-1").getOrNull() shouldBe 0
                backend.current(first).recipient shouldBe
                    Recipient("anon-1@anonymised.invalid", null, listOf(Channel.EMAIL))
                backend.current(other).accountPseudonym shouldBe null
            }

            test("an account deletion stops at an order it cannot store") {
                val backend = Backend()
                backend.stored(newOrder())
                backend.orders.lostUpdates = 3
                AnonymiseAccountOrders(backend.store)(SHOPPER, "anon-1").leftOrNull() shouldBe
                    OrderError.ConcurrentUpdate
            }

            test("the expiry job cancels payments pending for 30 minutes and releases their stock") {
                val backend = Backend()
                val expired = backend.stored(newOrder(placedAt = NOW.minus(Duration.ofMinutes(31))))
                val fresh = backend.stored(newOrder(placedAt = NOW.minus(Duration.ofMinutes(5))))
                backend.paid()

                ExpirePendingPayments(backend.store, backend.catalog, fixedClock())(10) shouldBe 1

                backend.current(expired).cancellation?.reason shouldBe CancellationReason.PAYMENT_EXPIRED
                backend.current(fresh).orderStatus shouldBe OrderStatus.PLACED
                backend.catalog.released shouldContainExactly listOf(RESERVATION)
                backend.events.published
                    .single()
                    .shouldBeInstanceOf<OrderEvent.Cancelled>()
                ExpirePendingPayments(backend.store, backend.catalog, fixedClock())(10) shouldBe 0
            }

            test("the expiry job counts nothing when an order cannot be stored") {
                val backend = Backend()
                backend.stored(newOrder(placedAt = NOW.minus(Duration.ofMinutes(31))))
                backend.orders.lostUpdates = 3
                ExpirePendingPayments(backend.store, backend.catalog, fixedClock())(10) shouldBe 0
                backend.catalog.released.shouldBeEmpty()
            }
        }
    })
