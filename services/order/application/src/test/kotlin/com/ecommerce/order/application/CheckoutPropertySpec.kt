package com.ecommerce.order.application

import com.ecommerce.order.domain.CancellationReason
import com.ecommerce.order.domain.IdempotencyRecord
import com.ecommerce.order.domain.OrderEvent
import com.ecommerce.order.domain.OrderStatus
import com.ecommerce.order.domain.PaymentStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.set
import io.kotest.property.checkAll
import java.time.Duration

/** Constitution V and FR-013: the checkout claim over generated failures and charge outcomes. */
class CheckoutPropertySpec :
    FunSpec({
        test("whatever fails, the claim never outlives the request and a retry with the key never doubles the order") {
            checkAll(Arb.set(Arb.enum<Step>(), 0..3), arbOutcome) { failing, outcome ->
                val world = CheckoutWorld(outcome = outcome)
                world.failAt(failing)

                val first = runCatching { world.place() }

                world.failAt(emptySet())
                val record = world.idempotency.records[SHOPPER to CHECKOUT_KEY]
                val placed = world.events.published.count { it is OrderEvent.Placed }
                if (placed == 0) {
                    first.isFailure shouldBe true
                    record shouldBe null
                    world.catalog.released shouldBe
                        if (world.catalog.reserved.isEmpty()) emptyList() else listOf(RESERVATION)
                    world.payment.charges.shouldBeEmpty()
                } else {
                    record.shouldNotBeNull().orderId shouldBe world.orderId
                    if (Step.COMPLETE !in failing) record.response shouldNotBe null
                }

                val longAgo = NOW.minus(IdempotencyRecord.CLAIM_TIMEOUT).minus(Duration.ofSeconds(1))
                record?.takeIf { it.response == null }?.let { abandoned ->
                    world.idempotency.records[SHOPPER to CHECKOUT_KEY] = abandoned.copy(createdAt = longAgo)
                }
                world.place().isRight() shouldBe true
                world.events.published.count { it is OrderEvent.Placed } shouldBe 1
                world.idempotency.records[SHOPPER to CHECKOUT_KEY]?.response shouldNotBe null
            }
        }

        test("a declined answer always carries the decline category; any other cancellation is order-cancelled") {
            checkAll(arbOrder()) { order ->
                val outcome = CheckoutOutcome.of(order)
                when (outcome) {
                    is CheckoutOutcome.Declined -> {
                        order.cancellation?.reason shouldBe CancellationReason.PAYMENT_FAILED
                        order.declineCategory shouldNotBe null
                    }

                    is CheckoutOutcome.Cancelled -> {
                        order.orderStatus shouldBe OrderStatus.CANCELLED
                        order.cancellation?.reason shouldNotBe CancellationReason.PAYMENT_FAILED
                    }

                    is CheckoutOutcome.Paid -> {
                        order.paymentStatus shouldBe PaymentStatus.APPROVED
                        order.orderStatus shouldNotBe OrderStatus.CANCELLED
                    }

                    is CheckoutOutcome.AwaitingPayment -> {
                        outcome.order.paymentStatus shouldBe PaymentStatus.PENDING
                    }
                }
                outcome.order shouldBe order
            }
        }
    })
