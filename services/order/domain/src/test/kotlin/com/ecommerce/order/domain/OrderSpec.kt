package com.ecommerce.order.domain

import com.ecommerce.order.domain.OrderFixtures.ATTEMPT
import com.ecommerce.order.domain.OrderFixtures.NOW
import com.ecommerce.order.domain.OrderFixtures.OPERATOR
import com.ecommerce.order.domain.OrderFixtures.SHOPPER
import com.ecommerce.order.domain.OrderFixtures.arbLines
import com.ecommerce.order.domain.OrderFixtures.arbOutcome
import com.ecommerce.order.domain.OrderFixtures.arbStep
import com.ecommerce.order.domain.OrderFixtures.line
import com.ecommerce.order.domain.OrderFixtures.paid
import com.ecommerce.order.domain.OrderFixtures.paidThen
import com.ecommerce.order.domain.OrderFixtures.placed
import com.ecommerce.order.domain.OrderFixtures.placement
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldStartWith
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.time.Duration
import java.util.UUID

class OrderSpec :
    FunSpec({
        context("placement") {
            test("an order is placed with a pending payment, a 30-minute clock and frozen totals") {
                checkAll(arbLines) { lines ->
                    val change = checkNotNull(Order.place(placement(lines), Order.DEFAULT_PAYMENT_WINDOW).getOrNull())
                    val order = change.order

                    order.orderStatus shouldBe OrderStatus.PLACED
                    order.paymentStatus shouldBe PaymentStatus.PENDING
                    order.paymentExpiresAt shouldBe NOW.plus(Duration.ofMinutes(30))
                    order.lines shouldBe lines
                    order.total.amountMinor shouldBe lines.sumOf { it.unitPrice.amountMinor * it.quantity.value }
                    order.lines.forEach { each ->
                        each.lineTotal.amountMinor shouldBe each.unitPrice.amountMinor * each.quantity.value
                    }
                    order.cancellation shouldBe null
                    order.accountPseudonym shouldBe null
                    order.paymentAttemptId shouldBe null
                    order.version shouldBe 0
                    order.history shouldContainExactly
                        listOf(
                            StatusChange(StatusKind.ORDER, null, "placed", NOW, SHOPPER.toString()),
                            StatusChange(StatusKind.PAYMENT, null, "pending", NOW, "system"),
                        )
                    change.events shouldContainExactly listOf(OrderEvent.Placed(order, NOW))
                }
            }

            test("an order without lines or with a zero total is refused") {
                val window = Order.DEFAULT_PAYMENT_WINDOW
                Order.place(placement(emptyList()), window).leftOrNull() shouldBe OrderError.EmptyCart
                Order.place(placement(listOf(line(priceMinor = 0))), window).leftOrNull() shouldBe
                    OrderError.Invalid("cart", "the order total must be positive")
            }

            test("the payment window given to the placement sets when the pending payment expires") {
                checkAll(Arb.long(1L..7_200L)) { seconds ->
                    val window = Duration.ofSeconds(seconds)
                    val order = checkNotNull(Order.place(placement(), window).getOrNull()).order

                    order.paymentExpiresAt shouldBe NOW.plus(window)
                    order.paymentExpired(NOW.plus(window).minusMillis(1)) shouldBe false
                    order.expirePayment(NOW.plus(window).minusMillis(1)).events.shouldBeEmpty()
                    order.paymentExpired(NOW.plus(window)) shouldBe true
                    order.expirePayment(NOW.plus(window)).order.cancellation shouldBe
                        Cancellation(CancellationReason.PAYMENT_EXPIRED, NOW.plus(window), "system")
                }
            }

            test("the payment window must be positive") {
                listOf(Duration.ZERO, Duration.ofSeconds(-1)).forEach { window ->
                    shouldThrow<IllegalArgumentException> { Order.place(placement(), window) }
                }
            }

            test("the aggregate rejects states outside the two-status model") {
                val order = placed()
                shouldThrow<IllegalArgumentException> { order.copy(lines = emptyList()) }
                shouldThrow<IllegalArgumentException> { order.copy(orderStatus = OrderStatus.CANCELLED) }
                shouldThrow<IllegalArgumentException> {
                    order.copy(cancellation = Cancellation(CancellationReason.OPERATOR, NOW, "system"))
                }
                shouldThrow<IllegalArgumentException> {
                    paid().copy(paymentStatus = PaymentStatus.PENDING, orderStatus = OrderStatus.SHIPPED)
                }
                shouldThrow<IllegalArgumentException> {
                    paidThen(OrderStatus.PREPARING).copy(paymentStatus = PaymentStatus.FAILED)
                }
                shouldThrow<IllegalArgumentException> {
                    paidThen(OrderStatus.CANCELLED).copy(paymentStatus = PaymentStatus.PENDING)
                }
                paidThen(OrderStatus.PREPARING).copy(paymentStatus = PaymentStatus.APPROVED).orderStatus shouldBe
                    OrderStatus.PREPARING
            }
        }

        context("payment outcomes") {
            test("an approval marks the payment approved once and publishes OrderPaid") {
                val change = placed().applyPayment(PaymentOutcome.Approved(ATTEMPT), NOW)
                val order = change.order

                order.paymentStatus shouldBe PaymentStatus.APPROVED
                order.orderStatus shouldBe OrderStatus.PLACED
                order.paymentAttemptId shouldBe ATTEMPT
                order.paidAt shouldBe NOW
                order.paymentExpiresAt shouldBe null
                order.history.last() shouldBe StatusChange(StatusKind.PAYMENT, "pending", "approved", NOW, "system")
                change.events shouldContainExactly listOf(OrderEvent.Paid(order, NOW))
            }

            test("a decline fails the payment and cancels the order with PAYMENT_FAILED") {
                val change =
                    placed().applyPayment(PaymentOutcome.Declined(ATTEMPT, DeclineCategory.INSUFFICIENT_FUNDS), NOW)
                val order = change.order

                order.paymentStatus shouldBe PaymentStatus.FAILED
                order.orderStatus shouldBe OrderStatus.CANCELLED
                order.cancellation shouldBe Cancellation(CancellationReason.PAYMENT_FAILED, NOW, "system")
                order.declineCategory shouldBe DeclineCategory.INSUFFICIENT_FUNDS
                order.paymentAttemptId shouldBe ATTEMPT
                order.paymentExpiresAt shouldBe null
                order.history.takeLast(2) shouldContainExactly
                    listOf(
                        StatusChange(StatusKind.PAYMENT, "pending", "failed", NOW, "system"),
                        StatusChange(
                            StatusKind.ORDER,
                            "placed",
                            "cancelled",
                            NOW,
                            "system",
                            CancellationReason.PAYMENT_FAILED,
                        ),
                    )
                change.events shouldContainExactly listOf(OrderEvent.PaymentFailed(order, NOW))
            }

            test("a decline without an attempt keeps the attempt already noted") {
                val noted = placed().applyPayment(PaymentOutcome.Pending(ATTEMPT), NOW).order
                val declined = noted.applyPayment(PaymentOutcome.Declined(null, DeclineCategory.CARD_EXPIRED), NOW)
                declined.order.paymentAttemptId shouldBe ATTEMPT
            }

            test("a pending outcome only notes the first attempt id") {
                val order = placed()
                val noted = order.applyPayment(PaymentOutcome.Pending(ATTEMPT), NOW)
                noted.order shouldBe order.copy(paymentAttemptId = ATTEMPT)
                noted.events.shouldBeEmpty()

                val other = PaymentAttemptId(UUID.randomUUID())
                noted.order.applyPayment(PaymentOutcome.Pending(other), NOW).order shouldBe noted.order
                order.applyPayment(PaymentOutcome.Pending(null), NOW).order shouldBe order
            }

            test("once resolved, any further outcome changes nothing and publishes nothing") {
                checkAll(arbOutcome, Arb.enum<DeclineCategory>()) { outcome, category ->
                    listOf(paid(), placed().applyPayment(PaymentOutcome.Declined(ATTEMPT, category), NOW).order)
                        .forEach { resolved ->
                            val again = resolved.applyPayment(outcome, NOW.plusSeconds(5))
                            again.order shouldBe resolved
                            again.events.shouldBeEmpty()
                        }
                }
            }
        }

        context("expiry") {
            test("a payment pending for 30 minutes or more cancels the order with PAYMENT_EXPIRED") {
                checkAll(Arb.long(0L..10_000L)) { seconds ->
                    val now = NOW.plus(Order.DEFAULT_PAYMENT_WINDOW).plusSeconds(seconds)
                    val order = placed()
                    order.paymentExpired(now) shouldBe true
                    val change = order.expirePayment(now)

                    change.order.orderStatus shouldBe OrderStatus.CANCELLED
                    change.order.paymentStatus shouldBe PaymentStatus.FAILED
                    change.order.cancellation shouldBe Cancellation(CancellationReason.PAYMENT_EXPIRED, now, "system")
                    change.order.paymentExpiresAt shouldBe null
                    val cancelled = change.events.single().shouldBeInstanceOf<OrderEvent.Cancelled>()
                    cancelled.refundRequired shouldBe false
                    cancelled.at shouldBe now
                }
            }

            test("before the window ends, or once the payment is resolved, nothing expires") {
                checkAll(Arb.long(1L..1_799L)) { seconds ->
                    val early = NOW.plusSeconds(Order.DEFAULT_PAYMENT_WINDOW.seconds - seconds)
                    placed().paymentExpired(early) shouldBe false
                    placed().expirePayment(early).events.shouldBeEmpty()
                    val approved = paid()
                    approved.expirePayment(NOW.plusSeconds(seconds * 100)).order shouldBe approved
                    paid().paymentExpired(NOW.plus(Duration.ofDays(1))) shouldBe false
                }
            }
        }

        context("refunds and anonymisation") {
            test("a refund is recorded once") {
                val cancelled = paidThen(OrderStatus.CANCELLED)
                val refundId = RefundId(UUID.randomUUID())
                val recorded = cancelled.recordRefund(refundId, NOW).order
                recorded.refund shouldBe Refund(refundId, NOW)
                recorded.recordRefund(RefundId(UUID.randomUUID()), NOW.plusSeconds(1)).order shouldBe recorded
            }

            test("anonymisation replaces the recipient and scrubs the address of terminal orders only") {
                val open = paid().anonymise("anon-4f9c2d71")
                open.events.shouldBeEmpty()
                open.order.accountPseudonym shouldBe "anon-4f9c2d71"
                open.order.recipient shouldBe Recipient("anon-4f9c2d71@anonymised.invalid", null, listOf(Channel.EMAIL))
                open.order.deliveryAddress shouldBe OrderFixtures.ADDRESS
                open.order.accountId shouldBe SHOPPER

                val delivered =
                    open.order
                        .transition(OrderStatus.PREPARING, OPERATOR, NOW)
                        .fold({ error("refused") }, { it.order })
                        .transition(OrderStatus.SHIPPED, OPERATOR, NOW)
                        .fold({ error("refused") }, { it.order })
                delivered.deliveryAddress shouldBe OrderFixtures.ADDRESS
                val done = checkNotNull(delivered.transition(OrderStatus.DELIVERED, OPERATOR, NOW).getOrNull()).order
                done.deliveryAddress shouldBe OrderFixtures.ADDRESS.scrubbed()

                val terminal = paidThen(OrderStatus.CANCELLED).anonymise("anon-1").order
                terminal.deliveryAddress shouldBe OrderFixtures.ADDRESS.scrubbed()
                terminal.anonymise("anon-2").order shouldBe terminal
            }
        }

        test("the payment deadline is placement plus the window exactly while the payment is pending") {
            checkAll(Arb.long(1L..7_200L), Arb.list(arbStep, 0..12)) { seconds, steps ->
                val window = Duration.ofSeconds(seconds)
                val placed = checkNotNull(Order.place(placement(), window).getOrNull()).order
                val order = steps.fold(placed) { current, step -> step(current) }

                (order.paymentExpiresAt != null) shouldBe (order.paymentStatus == PaymentStatus.PENDING)
                order.paymentExpiresAt?.let { it shouldBe order.placedAt.plus(window) }
            }
        }

        test("every sequence of commands keeps the invariants and an append-only history") {
            checkAll(Arb.list(arbStep, 1..12)) { steps ->
                steps.fold(placed()) { order, step ->
                    val next = step(order)
                    next.history shouldStartWith order.history
                    next.lines shouldBe order.lines
                    next.total shouldBe order.total
                    if (order.orderStatus.isTerminal) next.orderStatus shouldBe order.orderStatus
                    if (order.paymentStatus != PaymentStatus.PENDING) next.paymentStatus shouldBe order.paymentStatus
                    (next.cancellation != null) shouldBe (next.orderStatus == OrderStatus.CANCELLED)
                    next
                }
            }
        }

        test("an order is visible to its owner and to operators only") {
            val order = placed()
            order.isVisibleTo(Caller(SHOPPER, setOf(Role.SHOPPER))) shouldBe true
            order.isVisibleTo(Caller(OPERATOR, setOf(Role.OPERATOR))) shouldBe true
            order.isVisibleTo(Caller(OPERATOR, setOf(Role.SHOPPER))) shouldBe false
            order.isVisibleTo(Caller(SHOPPER, emptySet())) shouldBe false
        }
    })
