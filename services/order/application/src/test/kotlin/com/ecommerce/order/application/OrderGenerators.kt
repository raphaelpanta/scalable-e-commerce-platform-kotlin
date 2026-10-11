package com.ecommerce.order.application

import com.ecommerce.order.domain.AccountId
import com.ecommerce.order.domain.Caller
import com.ecommerce.order.domain.DeclineCategory
import com.ecommerce.order.domain.IdempotencyKey
import com.ecommerce.order.domain.Money
import com.ecommerce.order.domain.Order
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.OrderLine
import com.ecommerce.order.domain.OrderNumber
import com.ecommerce.order.domain.OrderStatus
import com.ecommerce.order.domain.PaymentAttemptId
import com.ecommerce.order.domain.PaymentOutcome
import com.ecommerce.order.domain.Placement
import com.ecommerce.order.domain.ProductId
import com.ecommerce.order.domain.Quantity
import com.ecommerce.order.domain.RefundId
import com.ecommerce.order.domain.Role
import com.ecommerce.order.domain.applyPayment
import com.ecommerce.order.domain.cancelByShopper
import com.ecommerce.order.domain.expirePayment
import com.ecommerce.order.domain.recordRefund
import com.ecommerce.order.domain.transition
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.subsequence
import io.kotest.property.arbitrary.uuid
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/** Line prices in minor units, as the checkout properties of `PlaceOrderTest` generate them. */
val arbPrices: Arb<List<Long>> = Arb.list(Arb.long(1L..50_000L), 1..5)

/** A charge outcome of any kind, with a fresh attempt id. */
val arbOutcome: Arb<PaymentOutcome> =
    arbitrary {
        val attempt = PaymentAttemptId(Arb.uuid().bind())
        when (Arb.int(0..2).bind()) {
            0 -> PaymentOutcome.Approved(attempt)
            1 -> PaymentOutcome.Declined(attempt, Arb.enum<DeclineCategory>().bind())
            else -> PaymentOutcome.Pending(attempt)
        }
    }

/** A new order of [owner] placed [minutesAgo] minutes before [NOW], one line per generated price. */
fun arbPlaced(
    owner: AccountId = SHOPPER,
    minutesAgo: Arb<Long> = Arb.long(0L..5L),
): Arb<Order> =
    arbitrary {
        val lines =
            arbPrices.bind().map { price ->
                OrderLine(ProductId(Arb.uuid().bind()), "SKU-1", "Mug", Money(price, "BRL"), Quantity(1))
            }
        val placement =
            Placement(
                OrderId(Arb.uuid().bind()),
                OrderNumber.of(LocalDate.of(2026, 10, 2), Arb.long(1L..9_999L).bind()),
                owner,
                lines,
                ADDRESS,
                RECIPIENT,
                "tok_sim_approve_4242",
                IdempotencyKey(Arb.uuid().bind()),
                RESERVATION,
                NOW.minus(Duration.ofMinutes(minutesAgo.bind())),
            )
        checkNotNull(Order.place(placement, Order.DEFAULT_PAYMENT_WINDOW).getOrNull()).order
    }

/** One lifecycle step an order may go through; refused steps leave it as it was. */
private val arbStep: Arb<(Order) -> Order> =
    arbitrary {
        val outcome = arbOutcome.bind()
        val target = Arb.enum<OrderStatus>().bind()
        val later = NOW.plus(Duration.ofMinutes(Arb.long(0L..60L).bind()))
        val steps: List<(Order) -> Order> =
            listOf(
                { order -> order.applyPayment(outcome, NOW).order },
                { order -> order.applyPayment(PaymentOutcome.Approved(CHECKOUT_ATTEMPT), NOW).order },
                { order -> order.transition(target, OPERATOR, NOW).fold({ order }, { it.order }) },
                { order -> order.cancelByShopper(NOW).fold({ order }, { it.order }) },
                { order -> order.expirePayment(later).order },
                { order -> order.recordRefund(RefundId(UUID.randomUUID()), NOW).order },
            )
        steps[Arb.int(steps.indices).bind()]
    }

/** An order of [owner] in any state the lifecycle reaches: placed, then up to eight random steps. */
fun arbOrder(
    owner: AccountId = SHOPPER,
    minutesAgo: Arb<Long> = Arb.long(0L..5L),
): Arb<Order> =
    arbitrary {
        Arb.list(arbStep, 0..8).bind().fold(arbPlaced(owner, minutesAgo).bind()) { order, step -> step(order) }
    }

/** A caller of any kind: the owner, another shopper, an operator, someone with both roles or with none. */
val arbCaller: Arb<Caller> =
    arbitrary {
        val account = Arb.of(SHOPPER, OTHER_SHOPPER, OPERATOR).bind()
        Caller(account, Arb.subsequence(Role.entries).map { roles -> roles.toSet() }.bind())
    }

/** Generated orders behind the order store, with fakes recording what a use case did to them. */
class OrderBackend(
    initial: List<Order>,
) {
    val orders = InMemoryOrders().apply { initial.forEach { stored[it.id] = it } }
    val events = RecordingEvents()
    val catalog = FakeCatalog()
    val store = OrderStore(orders, events, DirectTransactions(orders, events))

    fun current(id: OrderId): Order = checkNotNull(orders.stored[id])
}
