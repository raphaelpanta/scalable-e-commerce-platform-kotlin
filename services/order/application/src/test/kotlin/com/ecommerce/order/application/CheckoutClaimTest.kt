package com.ecommerce.order.application

import arrow.core.right
import com.ecommerce.order.domain.Caller
import com.ecommerce.order.domain.CancellationReason
import com.ecommerce.order.domain.DeclineCategory
import com.ecommerce.order.domain.IdempotencyRecord
import com.ecommerce.order.domain.OrderError
import com.ecommerce.order.domain.OrderEvent
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.OrderStatus
import com.ecommerce.order.domain.PaymentAttemptId
import com.ecommerce.order.domain.PaymentOutcome
import com.ecommerce.order.domain.PaymentStatus
import com.ecommerce.order.domain.Role
import com.ecommerce.order.domain.StoredResponse
import com.ecommerce.order.domain.expirePayment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.time.Duration
import java.util.UUID

private val SHOPPER_CALLER = Caller(SHOPPER, setOf(Role.SHOPPER))
private val OPERATOR_CALLER = Caller(OPERATOR, setOf(Role.OPERATOR))
private val ABANDONED_LONG_AGO: Duration = IdempotencyRecord.CLAIM_TIMEOUT.plusSeconds(1)

private val CHARGE_OUTCOMES =
    Arb.of(
        PaymentOutcome.Approved(CHECKOUT_ATTEMPT),
        PaymentOutcome.Declined(CHECKOUT_ATTEMPT, DeclineCategory.INSUFFICIENT_FUNDS),
        PaymentOutcome.Pending(null),
    )

private val CheckoutWorld.record: IdempotencyRecord? get() = idempotency.records[SHOPPER to CHECKOUT_KEY]

/** The claim of [world] as a crashed request would leave it: the order recorded, no answer, long abandoned. */
private fun CheckoutWorld.abandonClaim(
    orderId: OrderId?,
    hash: String = command().request.fingerprint(),
) {
    idempotency.records[SHOPPER to CHECKOUT_KEY] =
        IdempotencyRecord
            .claim(CHECKOUT_KEY, SHOPPER, hash, NOW.minus(ABANDONED_LONG_AGO))
            .copy(orderId = orderId)
}

/** Places the order with [step] failing; the failure propagates. */
private suspend fun CheckoutWorld.failingAt(step: Step) {
    failAt(setOf(step))
    shouldThrow<DependencyFailure> { place() }
    failAt(emptySet())
}

/** Runs the checkout until [started] holds, then cancels it, as a client disconnecting would. */
private suspend fun CheckoutWorld.cancelledWhen(started: () -> Boolean) =
    coroutineScope {
        val running = launch { place() }
        while (!started()) yield()
        running.cancelAndJoin()
    }

/** FR-013 and Constitution IV: the checkout claim never outlives a failed request, and never doubles an order. */
class CheckoutClaimTest :
    FunSpec({
        context("a failure before the order exists releases the claim and any reservation") {
            test("a dependency answering 503 before the reservation reserves nothing; the same key succeeds later") {
                listOf(Step.CART, Step.PRICING, Step.ADDRESS, Step.RECIPIENT, Step.RESERVE).forEach { step ->
                    val world = CheckoutWorld()

                    world.failingAt(step)

                    world.idempotency.records.shouldBeEmpty()
                    world.idempotency.released shouldContainExactly listOf(CHECKOUT_KEY)
                    world.catalog.released.shouldBeEmpty()
                    world.orders.stored.shouldBeEmpty()
                    world
                        .place()
                        .getOrNull()
                        .shouldBeInstanceOf<CheckoutResult.Completed>()
                        .outcome
                        .shouldBeInstanceOf<CheckoutOutcome.Paid>()
                    world.orders.stored.size shouldBe 1
                }
            }

            test("a database failure after the reservation releases the stock and the claim, storing nothing") {
                listOf(Step.ORDER_NUMBER, Step.INSERT, Step.RECORD_ORDER).forEach { step ->
                    val world = CheckoutWorld()

                    world.failingAt(step)

                    world.catalog.released shouldContainExactly listOf(RESERVATION)
                    world.idempotency.records.shouldBeEmpty()
                    world.idempotency.released shouldContainExactly listOf(CHECKOUT_KEY)
                    world.orders.stored.shouldBeEmpty()
                    world.events.published.shouldBeEmpty()
                    world.payment.charges.shouldBeEmpty()
                }
            }

            test("a request cancelled while reserving releases its claim") {
                val world = CheckoutWorld()
                world.catalog.reserving = { awaitCancellation() }

                world.cancelledWhen { world.accounts.addressRequests.isNotEmpty() }

                world.idempotency.records.shouldBeEmpty()
                world.idempotency.released shouldContainExactly listOf(CHECKOUT_KEY)
                world.orders.stored.shouldBeEmpty()
            }
        }

        context("once the order exists its id is on the claim, so nothing places a second order") {
            test("a failure after the order is stored completes the claim with it; a retry replays that order") {
                listOf(Step.CHARGE, Step.UPDATE).forEach { step ->
                    val world = CheckoutWorld()

                    world.failingAt(step)

                    world.record?.orderId shouldBe world.orderId
                    world.record?.response shouldBe StoredResponse(202, world.orderId.toString())
                    world.catalog.released.shouldBeEmpty()
                    world.idempotency.released.shouldBeEmpty()
                    world.place().getOrNull() shouldBe
                        CheckoutResult.Replayed(world.orderId, StoredResponse(202, world.orderId.toString()))
                    world.orders.stored.size shouldBe 1
                    world.payment.charges.size shouldBe 1
                }
            }

            test("a request cancelled during the charge leaves a replayable answer, not a free key") {
                val world = CheckoutWorld()
                world.payment.meanwhile = { awaitCancellation() }

                world.cancelledWhen { world.payment.charges.isNotEmpty() }

                world.record?.response shouldBe StoredResponse(202, world.orderId.toString())
                world.idempotency.released.shouldBeEmpty()
                world
                    .place()
                    .getOrNull()
                    .shouldBeInstanceOf<CheckoutResult.Replayed>()
                    .orderId shouldBe world.orderId
                world.orders.stored.size shouldBe 1
            }

            test("when even the claim cannot be completed, a takeover of the abandoned claim resumes its order") {
                val world = CheckoutWorld()
                world.failAt(setOf(Step.CHARGE, Step.COMPLETE))
                shouldThrow<DependencyFailure> { world.place() }
                world.failAt(emptySet())
                world.record?.orderId shouldBe world.orderId
                world.record?.response.shouldBeNull()
                world.abandonClaim(world.orderId)

                val resumed = world.place().getOrNull().shouldBeInstanceOf<CheckoutResult.Completed>()

                resumed.outcome
                    .shouldBeInstanceOf<CheckoutOutcome.AwaitingPayment>()
                    .order.id shouldBe world.orderId
                world.record?.response shouldBe StoredResponse(202, world.orderId.toString())
                world.orders.stored.size shouldBe 1
                world.payment.charges.size shouldBe 1
                world.catalog.reserved.size shouldBe 1
            }

            test("a takeover that fails before reading its order keeps the claim and the order for the next one") {
                val world = CheckoutWorld()
                world.failAt(setOf(Step.CHARGE, Step.COMPLETE))
                shouldThrow<DependencyFailure> { world.place() }
                world.abandonClaim(world.orderId)

                world.failingAt(Step.FIND_ORDER)

                world.record?.orderId shouldBe world.orderId
                world.idempotency.released.shouldBeEmpty()
                world.abandonClaim(world.orderId)
                world
                    .place()
                    .getOrNull()
                    .shouldBeInstanceOf<CheckoutResult.Completed>()
                    .outcome.order.id shouldBe
                    world.orderId
                world.events.published.count { it is OrderEvent.Placed } shouldBe 1
            }

            test("a failure while settling the stock answers the order as settled") {
                val world = CheckoutWorld()

                world.failingAt(Step.COMMIT)

                world.record?.response shouldBe StoredResponse(201, world.orderId.toString())
                world.orders.stored[world.orderId]?.paymentStatus shouldBe PaymentStatus.APPROVED
            }

            test("an abandoned claim with an order is not taken over by a different request") {
                val world = CheckoutWorld()
                world.abandonClaim(world.orderId, hash = "another request")

                world.place().leftOrNull() shouldBe OrderError.IdempotencyKeyReuse
                world.record?.orderId shouldBe world.orderId
            }

            test("a takeover whose recorded order does not exist checks out afresh") {
                val world = CheckoutWorld()
                world.abandonClaim(OrderId(UUID.randomUUID()))

                world
                    .place()
                    .getOrNull()
                    .shouldBeInstanceOf<CheckoutResult.Completed>()
                    .outcome.order.id shouldBe
                    world.orderId
                world.record?.response shouldBe StoredResponse(201, world.orderId.toString())
            }

            test("a settlement that keeps losing the lock answers the order as stored and keeps the claim") {
                val world = CheckoutWorld()
                val noted = PaymentAttemptId(UUID.randomUUID())
                world.orders.lostUpdates = 3
                world.payment.meanwhile = { request ->
                    world.orders.stored.computeIfPresent(
                        request.orderId,
                    ) { _, order -> order.copy(paymentAttemptId = noted) }
                }

                val result = world.place().getOrNull().shouldBeInstanceOf<CheckoutResult.Completed>()

                result.outcome
                    .shouldBeInstanceOf<CheckoutOutcome.AwaitingPayment>()
                    .order.paymentAttemptId shouldBe noted
                world.record?.response shouldBe StoredResponse(202, world.orderId.toString())
                world.catalog.released.shouldBeEmpty()
                world.idempotency.released.shouldBeEmpty()
            }
        }

        context("a cancellation that wins the race against the charge (US4/AC2)") {
            test("a shopper cancellation answers order-cancelled whatever the charge said, and is replayed") {
                checkAll(CHARGE_OUTCOMES) { outcome ->
                    val world = CheckoutWorld(outcome = outcome)
                    val cancel = CancelOwnOrder(world.store, world.catalog, fixedClock())
                    world.payment.meanwhile = { cancel(SHOPPER_CALLER, it.orderId) }

                    val result = world.place().getOrNull().shouldBeInstanceOf<CheckoutResult.Completed>()

                    val order = result.outcome.shouldBeInstanceOf<CheckoutOutcome.Cancelled>().order
                    order.cancellation?.reason shouldBe CancellationReason.SHOPPER_REQUEST
                    order.paymentStatus shouldBe PaymentStatus.FAILED
                    order.declineCategory.shouldBeNull()
                    world.events.published.map { it::class } shouldBe
                        listOf(OrderEvent.Placed::class, OrderEvent.Cancelled::class)
                    world.catalog.released shouldContainExactly listOf(RESERVATION)
                    world.catalog.committed.shouldBeEmpty()
                    world.cart.cleared.shouldBeEmpty()
                    world.place().getOrNull() shouldBe
                        CheckoutResult.Replayed(world.orderId, StoredResponse(409, world.orderId.toString()))
                }
            }

            test("an operator cancellation or the payment expiry winning the race is order-cancelled too") {
                val operator = CheckoutWorld()
                val transition = TransitionOrderStatus(operator.store, operator.catalog, fixedClock())
                operator.payment.meanwhile = { transition(OPERATOR_CALLER, it.orderId, OrderStatus.CANCELLED) }
                val expiry = CheckoutWorld()
                expiry.payment.meanwhile = { request ->
                    expiry.store.modify(request.orderId) { it.expirePayment(NOW.plus(Duration.ofHours(1))).right() }
                }

                val races =
                    listOf(operator to CancellationReason.OPERATOR, expiry to CancellationReason.PAYMENT_EXPIRED)
                for ((world, reason) in races) {
                    val outcome = (world.place().getOrNull() as CheckoutResult.Completed).outcome
                    outcome
                        .shouldBeInstanceOf<CheckoutOutcome.Cancelled>()
                        .order.cancellation
                        ?.reason shouldBe reason
                    world.record?.response?.status shouldBe 409
                }
            }

            test("a declined charge is still payment-declined") {
                val world = CheckoutWorld(outcome = PaymentOutcome.Declined(null, DeclineCategory.CARD_EXPIRED))
                (world.place().getOrNull() as CheckoutResult.Completed)
                    .outcome
                    .shouldBeInstanceOf<CheckoutOutcome.Declined>()
                    .order.cancellation
                    ?.reason shouldBe CancellationReason.PAYMENT_FAILED
            }
        }

        test("an identical request still running elsewhere is polled 50 times (virtual time), then refused")
            .config(coroutineTestScope = true) {
                val world = CheckoutWorld()
                world.idempotency.records[SHOPPER to CHECKOUT_KEY] =
                    IdempotencyRecord.claim(CHECKOUT_KEY, SHOPPER, world.command().request.fingerprint(), NOW)

                world.place().leftOrNull() shouldBe OrderError.IdempotencyKeyInUse

                world.idempotency.finds shouldBe 50
                world.orders.stored.shouldBeEmpty()
            }

        test("the purge deletes the records whose replay window ended, live ones stay") {
            val world = CheckoutWorld()
            world.place()
            val expired = IdempotencyRecord.claim(CHECKOUT_KEY, OTHER_SHOPPER, "old", NOW.minus(Duration.ofDays(1)))
            world.idempotency.records[OTHER_SHOPPER to CHECKOUT_KEY] = expired

            PurgeExpiredIdempotencyRecords(world.idempotency, fixedClock())() shouldBe 1L

            world.idempotency.records.keys shouldContainExactly setOf(SHOPPER to CHECKOUT_KEY)
            PurgeExpiredIdempotencyRecords(world.idempotency, fixedClock())() shouldBe 0L
        }
    })
