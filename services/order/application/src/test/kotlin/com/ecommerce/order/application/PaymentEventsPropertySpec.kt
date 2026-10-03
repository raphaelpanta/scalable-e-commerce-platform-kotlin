package com.ecommerce.order.application

import com.ecommerce.order.domain.CancellationReason
import com.ecommerce.order.domain.OrderError
import com.ecommerce.order.domain.OrderEvent
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.OrderStatus
import com.ecommerce.order.domain.PaymentOutcome
import com.ecommerce.order.domain.PaymentStatus
import com.ecommerce.order.domain.RefundId
import com.ecommerce.order.domain.paymentExpired
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import java.util.UUID

/** Constitution V: the payment expiry job, payment outcomes and refunds (FR-015) over generated orders. */
class PaymentEventsPropertySpec :
    FunSpec({
        test("the expiry job cancels exactly the payments pending for 30 minutes, in batches, each once") {
            checkAll(Arb.list(arbOrder(minutesAgo = Arb.long(0L..60L)), 0..8), Arb.int(1..4)) { generated, batch ->
                // Distinct ids even once the generated ones are shrunk.
                val orders = generated.mapIndexed { index, order -> order.copy(id = OrderId(UUID(0L, index + 1L))) }
                val backend = OrderBackend(orders)
                val expire = ExpirePendingPayments(backend.store, backend.catalog, fixedClock())
                val due = orders.filter { it.paymentExpired(NOW) }.map { it.id }.toSet()

                var expired = 0
                do {
                    val count = expire(batch)
                    count shouldBeLessThanOrEqual batch
                    expired += count
                } while (count == batch)
                expire(batch) shouldBe 0

                expired shouldBe due.size
                orders.forEach { order ->
                    val now = backend.current(order.id)
                    if (order.id in due) {
                        now.orderStatus shouldBe OrderStatus.CANCELLED
                        now.paymentStatus shouldBe PaymentStatus.FAILED
                        now.cancellation?.reason shouldBe CancellationReason.PAYMENT_EXPIRED
                    } else {
                        now shouldBe order
                    }
                }
                backend.catalog.released.size shouldBe due.size
                backend.events.published
                    .map { it.order.id }
                    .toSet() shouldBe due
                backend.events.published.forEach { it.shouldBeInstanceOf<OrderEvent.Cancelled>() }
            }
        }

        test("a payment outcome applies only while the payment is pending; duplicates and late ones change nothing") {
            checkAll(arbOrder(), Arb.list(arbOutcome, 1..4)) { order, outcomes ->
                val backend = OrderBackend(listOf(order))
                val apply = ApplyPaymentOutcome(backend.store, fixedClock())

                val changes = outcomes.map { apply(order.id, it).getOrNull()?.changed }

                val decisive = outcomes.firstOrNull { it !is PaymentOutcome.Pending }
                val after = backend.current(order.id)
                if (order.paymentStatus != PaymentStatus.PENDING) {
                    after shouldBe order
                    changes.all { it == false } shouldBe true
                } else {
                    after.paymentStatus shouldBe
                        when (decisive) {
                            is PaymentOutcome.Approved -> PaymentStatus.APPROVED
                            is PaymentOutcome.Declined -> PaymentStatus.FAILED
                            else -> PaymentStatus.PENDING
                        }
                    if (decisive is PaymentOutcome.Declined) {
                        after.cancellation?.reason shouldBe CancellationReason.PAYMENT_FAILED
                        after.declineCategory shouldBe decisive.category
                    }
                }
                backend.events.published.size shouldBeLessThanOrEqual 1
                apply(OrderId(UUID.randomUUID()), outcomes.first()).leftOrNull() shouldBe
                    OrderError.OrderNotFound
            }
        }

        test("a refund is recorded once, whatever is delivered after it") {
            checkAll(arbOrder(), Arb.list(Arb.uuid(), 1..4)) { order, refunds ->
                val backend = OrderBackend(listOf(order))
                val record = RecordRefund(backend.store, fixedClock())

                val changed = refunds.count { record(order.id, RefundId(it)).getOrNull()?.changed == true }

                val expected = order.refund?.refundId ?: RefundId(refunds.first())
                backend.current(order.id).refund?.refundId shouldBe expected
                changed shouldBe if (order.refund == null) 1 else 0
                backend.events.published.shouldBeEmpty()
                backend.current(order.id).copy(refund = order.refund, version = order.version) shouldBe order
            }
        }
    })
