package com.ecommerce.order.application

import com.ecommerce.order.domain.Caller
import com.ecommerce.order.domain.Cart
import com.ecommerce.order.domain.ChangedLine
import com.ecommerce.order.domain.CheckoutRequest
import com.ecommerce.order.domain.DeclineCategory
import com.ecommerce.order.domain.IdempotencyKey
import com.ecommerce.order.domain.IdempotencyRecord
import com.ecommerce.order.domain.Money
import com.ecommerce.order.domain.Order
import com.ecommerce.order.domain.OrderError
import com.ecommerce.order.domain.OrderEvent
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.OrderNumber
import com.ecommerce.order.domain.OrderStatus
import com.ecommerce.order.domain.PaymentAttemptId
import com.ecommerce.order.domain.PaymentOutcome
import com.ecommerce.order.domain.PaymentStatus
import com.ecommerce.order.domain.Quantity
import com.ecommerce.order.domain.Role
import com.ecommerce.order.domain.StockLine
import com.ecommerce.order.domain.StockShortage
import com.ecommerce.order.domain.StoredResponse
import com.ecommerce.order.domain.UnavailableLine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

private val ATTEMPT = CHECKOUT_ATTEMPT
private val KEY = CHECKOUT_KEY
private const val TOKEN = CARD_TOKEN

private typealias World = CheckoutWorld

class PlaceOrderTest :
    FunSpec({
        test("an approved checkout places the order at frozen prices, commits the stock and empties the cart") {
            val world = World()
            world.catalog.prices = checkNotNull(world.cart.cart).lines.map { priceOf(it, it.priceAtAdd.amountMinor) }

            val result = world.place().getOrNull().shouldBeInstanceOf<CheckoutResult.Completed>()

            val order = result.outcome.shouldBeInstanceOf<CheckoutOutcome.Paid>().order
            order.id shouldBe world.orderId
            order.number shouldBe OrderNumber.of(LocalDate.of(2026, 10, 2), 1)
            order.orderStatus shouldBe OrderStatus.PLACED
            order.paymentStatus shouldBe PaymentStatus.APPROVED
            order.total shouldBe Money(19800, "BRL")
            order.deliveryAddress shouldBe ADDRESS
            order.recipient shouldBe RECIPIENT
            order.reservationId shouldBe RESERVATION
            order.paymentMethodRef shouldBe TOKEN
            order.idempotencyKey shouldBe KEY
            order.placedAt shouldBe NOW
            world.orders.stored[order.id]?.paymentStatus shouldBe PaymentStatus.APPROVED
            world.events.published.map { it::class } shouldBe listOf(OrderEvent.Placed::class, OrderEvent.Paid::class)
            world.catalog.reserved shouldContainExactly
                listOf(world.orderId to order.lines.map { StockLine(it.productId, it.quantity) })
            world.catalog.committed shouldContainExactly listOf(RESERVATION)
            world.catalog.released.shouldBeEmpty()
            world.cart.cleared shouldContainExactly listOf(SHOPPER)
            world.accounts.addressRequests shouldContainExactly listOf(SHOPPER to ADDRESS_ID)
            world.payment.charges shouldContainExactly
                listOf(ChargeRequest(world.orderId, SHOPPER, Money(19800, "BRL"), TOKEN, KEY))
            val record =
                world.idempotency.records.values
                    .single()
            record.orderId shouldBe world.orderId
            record.response shouldBe StoredResponse(201, world.orderId.toString())
            record.requestHash shouldBe world.command().request.fingerprint()
            record.createdAt shouldBe NOW
        }

        test("the order freezes the current catalogue prices, whatever the cart recorded") {
            checkAll(Arb.list(Arb.long(1L..50_000L), 1..5)) { prices ->
                val lines = prices.map { cartLine(it * 2) }
                val world = World(lines)
                world.catalog.prices = lines.map { priceOf(it, it.priceAtAdd.amountMinor / 2) }

                val outcome = (world.place().getOrNull() as CheckoutResult.Completed).outcome
                outcome.order.lines.map { it.unitPrice.amountMinor } shouldBe prices
                outcome.order.total.amountMinor shouldBe prices.sum()
            }
        }

        test("a declined payment cancels the order, releases the stock and keeps the cart") {
            val world = World(outcome = PaymentOutcome.Declined(ATTEMPT, DeclineCategory.CARD_REJECTED))

            val result = world.place().getOrNull().shouldBeInstanceOf<CheckoutResult.Completed>()

            val order = result.outcome.shouldBeInstanceOf<CheckoutOutcome.Declined>().order
            order.orderStatus shouldBe OrderStatus.CANCELLED
            order.declineCategory shouldBe DeclineCategory.CARD_REJECTED
            world.events.published.map { it::class } shouldBe
                listOf(OrderEvent.Placed::class, OrderEvent.PaymentFailed::class)
            world.catalog.released shouldContainExactly listOf(RESERVATION)
            world.catalog.committed.shouldBeEmpty()
            world.cart.cleared.shouldBeEmpty()
            world.idempotency.records.values
                .single()
                .response shouldBe StoredResponse(422, world.orderId.toString())
        }

        test("the pending payment of a placed order expires after the configured payment window") {
            listOf(Duration.ofSeconds(20), Order.DEFAULT_PAYMENT_WINDOW, Duration.ofHours(2)).forEach { window ->
                val world = World(outcome = PaymentOutcome.Pending(null), paymentWindow = window)

                val result = world.place().getOrNull().shouldBeInstanceOf<CheckoutResult.Completed>()

                val order = result.outcome.order
                order.placedAt shouldBe NOW
                order.paymentExpiresAt shouldBe NOW.plus(window)
                world.orders.stored
                    .getValue(order.id)
                    .paymentExpiresAt shouldBe NOW.plus(window)
            }
        }

        test("an unreachable provider leaves the order placed with a pending payment and the stock reserved") {
            val world = World(outcome = PaymentOutcome.Pending(null))

            val result = world.place().getOrNull().shouldBeInstanceOf<CheckoutResult.Completed>()

            result.outcome
                .shouldBeInstanceOf<CheckoutOutcome.AwaitingPayment>()
                .order.paymentStatus shouldBe
                PaymentStatus.PENDING
            world.events.published.map { it::class } shouldBe listOf(OrderEvent.Placed::class)
            world.catalog.committed.shouldBeEmpty()
            world.catalog.released.shouldBeEmpty()
            world.cart.cleared.shouldBeEmpty()
            world.idempotency.records.values
                .single()
                .response shouldBe StoredResponse(202, world.orderId.toString())
        }

        test("a settlement that loses the lock to a payment event is applied to the fresh state") {
            val world = World()
            world.orders.lostUpdates = 1

            val result = world.place().getOrNull().shouldBeInstanceOf<CheckoutResult.Completed>()

            result.outcome.shouldBeInstanceOf<CheckoutOutcome.Paid>()
        }

        test("only shoppers check out") {
            val world = World()
            world.place(world.command(roles = setOf(Role.OPERATOR))).leftOrNull() shouldBe OrderError.Forbidden
            world.idempotency.records.shouldBeEmpty()
            world.catalog.reserved.shouldBeEmpty()
        }

        context("refusals store no idempotency record and create nothing") {
            suspend fun World.refusedWith(expected: OrderError) {
                place().leftOrNull() shouldBe expected
                idempotency.records.shouldBeEmpty()
                idempotency.released shouldContainExactly listOf(KEY)
                orders.stored.shouldBeEmpty()
                events.published.shouldBeEmpty()
                payment.charges.shouldBeEmpty()
            }

            test("no cart or an empty cart") {
                World().apply { cart.cart = null }.refusedWith(OrderError.EmptyCart)
                World(lines = emptyList()).refusedWith(OrderError.EmptyCart)
            }

            test("a stale cart revision lists the changed lines and the current revision; nothing is reserved") {
                val stale = cartLine(1500, name = "Mug")
                val same = cartLine(900, name = "Plate")
                val world = World(listOf(stale, same))
                world.catalog.prices = listOf(priceOf(stale, 1700), priceOf(same))

                world.place(world.command(revision = "rev-0")).leftOrNull() shouldBe
                    OrderError.PriceChanged(
                        listOf(ChangedLine(stale.lineId, stale.productId, Money(1500, "BRL"), Money(1700, "BRL"))),
                        "rev-1",
                    )
                world.catalog.reserved.shouldBeEmpty()
                world.accounts.addressRequests.shouldBeEmpty()
                world.idempotency.records.shouldBeEmpty()
            }

            test("an unknown address or account") {
                World().apply { accounts.address = null }.refusedWith(OrderError.AddressNotFound)
                World().apply { accounts.recipient = null }.refusedWith(OrderError.AccountNotFound)
            }

            test("a withdrawn product is unavailable without reserving anything") {
                val line = cartLine(1000, quantity = 2, name = "Vase")
                val world = World(listOf(line))
                world.catalog.prices = listOf(priceOf(line, active = false))
                world.refusedWith(OrderError.InsufficientStock(listOf(UnavailableLine(line.productId, "Vase", 2, 0))))
                world.catalog.reserved.shouldBeEmpty()
            }

            test("a stock shortage names the unavailable lines") {
                val line = cartLine(1000, quantity = 2, name = "Vase")
                val world = World(listOf(line))
                world.catalog.reservation = ReservationResult.Refused(listOf(StockShortage(line.productId, 2, 1)))
                world.refusedWith(OrderError.InsufficientStock(listOf(UnavailableLine(line.productId, "Vase", 2, 1))))
            }

            test("a free order is refused and its reservation released") {
                val world = World(listOf(cartLine(0)))
                world.refusedWith(OrderError.Invalid("cart", "the order total must be positive"))
                world.catalog.released shouldContainExactly listOf(RESERVATION)
            }
        }

        context("idempotency") {
            test("the same request with the same key replays the stored answer without a second order") {
                val world = World()
                world.place()

                world.place().getOrNull() shouldBe
                    CheckoutResult.Replayed(world.orderId, StoredResponse(201, world.orderId.toString()))
                world.orders.stored.size shouldBe 1
                world.payment.charges.size shouldBe 1
            }

            test("the same key with a different request is refused") {
                val world = World()
                world.place()

                world.place(world.command(token = "tok_sim_other")).leftOrNull() shouldBe OrderError.IdempotencyKeyReuse
                world.orders.stored.size shouldBe 1
            }

            test("a duplicate waits for the first request and then replays its answer") {
                val world = World()
                val stored = StoredResponse(201, "first")
                val claim = IdempotencyRecord.claim(KEY, SHOPPER, world.command().request.fingerprint(), NOW)
                world.idempotency.records[SHOPPER to KEY] = claim
                world.idempotency.pendingFinds += listOf(claim, null)
                world.idempotency.pendingFinds += claim.copy(orderId = world.orderId, response = stored)

                world.place().getOrNull() shouldBe CheckoutResult.Replayed(world.orderId, stored)
                world.orders.stored.shouldBeEmpty()
            }

            test("a duplicate of a different request still running is refused at once") {
                val world = World()
                world.idempotency.records[SHOPPER to KEY] = IdempotencyRecord.claim(KEY, SHOPPER, "another", NOW)

                world.place().leftOrNull() shouldBe OrderError.IdempotencyKeyReuse
            }
        }

        test("the outcome of a settled order follows its payment status") {
            val world = World()
            val order = ((world.place().getOrNull() as CheckoutResult.Completed).outcome).order
            CheckoutOutcome.of(order).shouldBeInstanceOf<CheckoutOutcome.Paid>()
            CheckoutOutcome
                .of(
                    order.copy(paymentStatus = PaymentStatus.PENDING, paidAt = null),
                ).shouldBeInstanceOf<CheckoutOutcome.AwaitingPayment>()
            world.catalog.pricingRequests.single() shouldBe checkNotNull(world.cart.cart).lines.map { it.productId }
            world.catalog.reserved
                .single()
                .second
                .map { it.quantity } shouldBe listOf(Quantity(1), Quantity(2))
        }
    })
