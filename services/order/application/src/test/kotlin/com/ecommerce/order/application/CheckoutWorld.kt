package com.ecommerce.order.application

import com.ecommerce.order.domain.Caller
import com.ecommerce.order.domain.Cart
import com.ecommerce.order.domain.CartLine
import com.ecommerce.order.domain.CheckoutRequest
import com.ecommerce.order.domain.IdempotencyKey
import com.ecommerce.order.domain.Order
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.PaymentAttemptId
import com.ecommerce.order.domain.PaymentOutcome
import com.ecommerce.order.domain.Role
import java.time.Clock
import java.time.Duration
import java.util.UUID

val CHECKOUT_ATTEMPT = PaymentAttemptId(UUID.fromString("c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50"))
val CHECKOUT_KEY = IdempotencyKey(UUID.fromString("6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f"))
const val CARD_TOKEN = "tok_sim_approve_4242"

/** A checkout against fakes of every port. */
class CheckoutWorld(
    lines: List<CartLine> = listOf(cartLine(14900), cartLine(2450, quantity = 2)),
    outcome: PaymentOutcome = PaymentOutcome.Approved(CHECKOUT_ATTEMPT),
    clock: Clock = fixedClock(),
    paymentWindow: Duration = Order.DEFAULT_PAYMENT_WINDOW,
) {
    val cart = FakeCart(Cart("rev-1", lines))
    val catalog = FakeCatalog(prices = lines.map { priceOf(it) })
    val payment = FakePayment(outcome)
    val accounts = FakeAccounts()
    val orders = InMemoryOrders()
    val events = RecordingEvents()
    val idempotency = InMemoryIdempotency()
    val store = OrderStore(orders, events, DirectTransactions(orders, events))
    val orderId = OrderId(UUID.randomUUID())
    val placeOrder =
        PlaceOrder(
            CheckoutPorts(cart, catalog, payment, accounts),
            store,
            idempotency,
            TEST_RESPONSES,
            { orderId },
            clock,
            paymentWindow,
        )

    /** Makes every port call of [steps] throw, as a dependency answering 503 or a failing database would. */
    fun failAt(steps: Set<Step>) {
        cart.failing = steps
        catalog.failing = steps
        payment.failing = steps
        accounts.failing = steps
        orders.failing = steps
        idempotency.failing = steps
    }

    fun command(
        revision: String = "rev-1",
        token: String = CARD_TOKEN,
        roles: Set<Role> = setOf(Role.SHOPPER),
    ): PlaceOrderCommand =
        PlaceOrderCommand(Caller(SHOPPER, roles), CHECKOUT_KEY, CheckoutRequest(ADDRESS_ID, revision, "card", token))

    suspend fun place(command: PlaceOrderCommand = command()) = placeOrder(command)
}
