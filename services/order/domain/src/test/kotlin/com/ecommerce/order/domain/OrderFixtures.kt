package com.ecommerce.order.domain

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.uuid
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Builders and generators shared by the domain specs. */
object OrderFixtures {
    val NOW: Instant = Instant.parse("2026-10-02T10:15:00Z")
    val SHOPPER = AccountId(UUID.fromString("7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"))
    val OPERATOR = AccountId(UUID.fromString("e5a1c3b7-2d40-4f9e-8b16-3c7d9a0f1e22"))
    val ATTEMPT = PaymentAttemptId(UUID.fromString("c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50"))
    val KEY = IdempotencyKey(UUID.fromString("6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f"))
    const val BRL = "BRL"

    val ADDRESS =
        DeliveryAddress("Ada Lovelace", "12 Analytical Street", "Flat 2", "London", "England", "N1 9GU", "GB")
    val RECIPIENT = Recipient("ada@example.test", "+5511987654321", listOf(Channel.EMAIL, Channel.SMS))

    fun line(
        priceMinor: Long = 14900,
        quantity: Int = 1,
        productId: UUID = UUID.randomUUID(),
    ): OrderLine =
        OrderLine(ProductId(productId), "SKU-1", "Espresso Machine", Money(priceMinor, BRL), Quantity(quantity))

    fun placement(
        lines: List<OrderLine> = listOf(line()),
        at: Instant = NOW,
    ): Placement =
        Placement(
            id = OrderId(UUID.randomUUID()),
            number = OrderNumber.of(java.time.LocalDate.of(2026, 10, 2), 1),
            accountId = SHOPPER,
            lines = lines,
            deliveryAddress = ADDRESS,
            recipient = RECIPIENT,
            paymentMethodRef = "tok_sim_approve_4242",
            idempotencyKey = KEY,
            reservationId = ReservationId(UUID.randomUUID()),
            placedAt = at,
        )

    fun placed(lines: List<OrderLine> = listOf(line())): Order =
        checkNotNull(Order.place(placement(lines), Order.DEFAULT_PAYMENT_WINDOW).getOrNull()).order

    fun paid(): Order = placed().applyPayment(PaymentOutcome.Approved(ATTEMPT), NOW).order

    fun declined(): Order =
        placed().applyPayment(PaymentOutcome.Declined(ATTEMPT, DeclineCategory.CARD_REJECTED), NOW).order

    /** An order moved along [path] by an operator, starting from a paid order. */
    fun paidThen(vararg path: OrderStatus): Order =
        path.fold(paid()) { order, target -> checkNotNull(order.transition(target, OPERATOR, NOW).getOrNull()).order }

    /** Every order state the lifecycle can reach. */
    fun reachableStates(): List<Order> =
        listOf(
            placed(),
            paid(),
            declined(),
            paidThen(OrderStatus.PREPARING),
            paidThen(OrderStatus.PREPARING, OrderStatus.SHIPPED),
            paidThen(OrderStatus.PREPARING, OrderStatus.SHIPPED, OrderStatus.DELIVERED),
            paidThen(OrderStatus.CANCELLED),
            paidThen(OrderStatus.PREPARING, OrderStatus.CANCELLED),
            checkNotNull(placed().cancelByShopper(NOW).getOrNull()).order,
            placed().expirePayment(NOW.plus(Order.DEFAULT_PAYMENT_WINDOW)).order,
        )

    val arbLine: Arb<OrderLine> =
        arbitrary {
            line(Arb.long(1L..1_000_000L).bind(), Arb.int(Quantity.MIN..Quantity.MAX).bind(), Arb.uuid().bind())
        }

    val arbLines: Arb<List<OrderLine>> = Arb.list(arbLine, 1..10)

    val arbState: Arb<Order> = Arb.element(reachableStates())

    val arbOutcome: Arb<PaymentOutcome> =
        arbitrary {
            when (Arb.int(0..2).bind()) {
                0 -> PaymentOutcome.Approved(PaymentAttemptId(Arb.uuid().bind()))
                1 -> PaymentOutcome.Declined(PaymentAttemptId(Arb.uuid().bind()), Arb.enum<DeclineCategory>().bind())
                else -> PaymentOutcome.Pending(PaymentAttemptId(Arb.uuid().bind()))
            }
        }

    /** One random command applied to an order; refused commands leave it unchanged. */
    val arbStep: Arb<(Order) -> Order> =
        arbitrary {
            val outcome = arbOutcome.bind()
            val target = Arb.enum<OrderStatus>().bind()
            val minutes = Arb.long(0L..60L).bind()
            val steps: List<(Order) -> Order> =
                listOf(
                    { order -> order.applyPayment(outcome, NOW).order },
                    { order -> order.transition(target, OPERATOR, NOW).fold({ order }, { it.order }) },
                    { order -> order.cancelByShopper(NOW).fold({ order }, { it.order }) },
                    { order -> order.expirePayment(NOW.plus(Duration.ofMinutes(minutes))).order },
                    { order -> order.anonymise("anon-4f9c2d71").order },
                    { order -> order.recordRefund(RefundId(UUID.randomUUID()), NOW).order },
                )
            steps[Arb.int(steps.indices).bind()]
        }
}
