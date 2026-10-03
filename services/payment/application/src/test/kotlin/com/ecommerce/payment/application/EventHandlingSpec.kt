package com.ecommerce.payment.application

import com.ecommerce.payment.domain.DeclineCategory
import com.ecommerce.payment.domain.IdempotencyKey
import com.ecommerce.payment.domain.PaymentAttemptId
import com.ecommerce.payment.domain.PaymentError
import com.ecommerce.payment.domain.PaymentEvent
import com.ecommerce.payment.domain.ProviderDecision
import com.ecommerce.payment.domain.RefundRecord
import com.ecommerce.payment.domain.RefundRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.util.UUID

private val LATER: Instant = NOW.plusSeconds(60)

private fun cancelled(
    charge: com.ecommerce.payment.domain.PaymentAttempt,
    refundRequired: Boolean = true,
    paymentId: PaymentAttemptId? = charge.id,
): CancelledOrder = CancelledOrder(charge.orderId, refundRequired, paymentId, ADA_CONTACT)

/** The OrderPlaced and OrderCancelled consumers: convergence with the synchronous calls, one refund per order. */
class EventHandlingSpec :
    FunSpec({
        test("OrderPlaced charges an order the synchronous call never reached") {
            val backend = Backend()
            val request = chargeRequest()

            val outcome = backend.chargePlacedOrder(request)

            outcome.shouldBeInstanceOf<PlacedOrderCharge.Charged>().attempt shouldBe
                backend.attempts.stored.values
                    .single()
            backend.events.published shouldHaveSize 1
        }

        test("OrderPlaced converges on the attempt of the synchronous charge with the same checkout key") {
            val backend = Backend()
            val request = chargeRequest()
            val attempt = backend.authoriseCharge(request).getOrNull()!!.value

            backend.chargePlacedOrder(request) shouldBe PlacedOrderCharge.Converged(attempt)
            backend.chargePlacedOrder(request) shouldBe PlacedOrderCharge.Converged(attempt)

            backend.attempts.stored.values shouldHaveSize 1
            backend.provider.charges shouldHaveSize 1
            backend.events.published shouldHaveSize 1
        }

        test("OrderPlaced under a new key for an order already approved is refused, never a second charge") {
            val backend = Backend()
            val charge = backend.approvedCharge()

            backend.chargePlacedOrder(chargeRequest(charge.orderId)) shouldBe
                PlacedOrderCharge.Refused(PaymentError.AlreadyCharged)
            backend.attempts.stored.values shouldHaveSize 1
        }

        test("OrderCancelled with an approved payment refunds the charge once, under a key derived from the order") {
            val backend = Backend()
            val charge = backend.approvedCharge()

            val outcome = backend.settleCancelledOrder(cancelled(charge))

            val refund = outcome.shouldBeInstanceOf<CancellationRefund.Refunded>().refund
            refund.attemptId shouldBe charge.id
            refund.amount shouldBe charge.amount
            refund.idempotencyKey shouldBe IdempotencyKey.refundOf(charge.orderId)
            refund.announcedAt shouldBe NOW
            backend.events.published shouldContainExactly listOf(PaymentEvent.RefundRecorded(refund, ADA_CONTACT))

            backend.settleCancelledOrder(cancelled(charge)) shouldBe CancellationRefund.AlreadyRefunded(refund)
            backend.refunds.stored.values shouldHaveSize 1
            backend.events.published shouldHaveSize 1
        }

        test("OrderCancelled finds the approved charge of the order when the event names no usable payment") {
            val backend = Backend()
            val charge = backend.approvedCharge()
            val pendingOfOrder = backend.stored(attemptFor(chargeRequest(charge.orderId), ProviderDecision.Unreachable))

            listOf(null, PaymentAttemptId(UUID.randomUUID()), pendingOfOrder.id).forEach { named ->
                val fresh = Backend()
                fresh.stored(charge)
                fresh.stored(pendingOfOrder)
                fresh
                    .settleCancelledOrder(cancelled(charge, paymentId = named))
                    .shouldBeInstanceOf<CancellationRefund.Refunded>()
                    .refund.attemptId shouldBe charge.id
            }
            val foreign = backend.approvedCharge()
            backend
                .settleCancelledOrder(cancelled(charge, paymentId = foreign.id))
                .shouldBeInstanceOf<CancellationRefund.Refunded>()
                .refund.attemptId shouldBe charge.id
        }

        test("OrderCancelled without an approved payment, or without a charge here, refunds nothing") {
            val backend = Backend()
            val declined =
                backend.stored(
                    attemptFor(chargeRequest(), ProviderDecision.Declined(DeclineCategory.CARD_REJECTED, CHARGE_REF)),
                )

            backend.settleCancelledOrder(cancelled(declined, refundRequired = false)) shouldBe
                CancellationRefund.NotRequired
            backend.settleCancelledOrder(cancelled(declined)) shouldBe CancellationRefund.NoApprovedCharge
            backend.settleCancelledOrder(
                CancelledOrder(newOrder(), true, null, ADA_CONTACT),
            ) shouldBe CancellationRefund.NoApprovedCharge
            backend.refunds.stored.values
                .shouldBeEmpty()
            backend.events.published.shouldBeEmpty()
        }

        test("a refund recorded over HTTP before the cancellation event is announced by it, once") {
            val backend = Backend()
            val charge = backend.approvedCharge()
            val early =
                backend
                    .recordRefund(
                        RefundRequest(charge.orderId, charge.id, charge.amount, newKey()),
                    ).getOrNull()!!
            val laterBackend = Backend()
            laterBackend.stored(charge)
            laterBackend.stored(early.value)
            val clocked = SettleCancelledOrder(laterBackend.ledger, laterBackend.recordRefund, fixedClock(LATER))

            val announced = clocked(cancelled(charge)).shouldBeInstanceOf<CancellationRefund.Announced>().refund

            announced shouldBe early.value.copy(announcedAt = LATER)
            laterBackend.events.published shouldContainExactly
                listOf(PaymentEvent.RefundRecorded(announced, ADA_CONTACT))
            laterBackend.refunds.stored[announced.id] shouldBe announced
            clocked(cancelled(charge)) shouldBe CancellationRefund.AlreadyRefunded(announced)
            laterBackend.events.published shouldHaveSize 1
            laterBackend.provider.refunds.shouldBeEmpty()
        }

        test("an announcement lost to a concurrent consumer publishes nothing") {
            val backend = Backend()
            val charge = backend.approvedCharge()
            val refund =
                backend.stored(
                    RefundRecord.of(
                        backend.ids.nextRefund(),
                        charge,
                        RefundRequest(charge.orderId, charge.id, charge.amount, newKey()),
                        REFUND_REF,
                        NOW,
                    ),
                )
            backend.refunds.announcedElsewhere = true

            backend.settleCancelledOrder(cancelled(charge)) shouldBe CancellationRefund.AlreadyRefunded(refund)
            backend.events.published.shouldBeEmpty()
        }

        test("OrderCancelled remembers the order with the owner's contact, under the order's lock, once") {
            val backend = Backend()
            val orderId = newOrder()

            backend.settleCancelledOrder(CancelledOrder(orderId, false, null, ADA_CONTACT)) shouldBe
                CancellationRefund.NotRequired
            backend.settleCancelledOrder(CancelledOrder(orderId, false, null, ADA_CONTACT.copy(email = "x@y.test")))

            backend.cancellations.stored.values
                .toList() shouldContainExactly
                listOf(CancelledOrderRecord(orderId, ADA_CONTACT, NOW))
            backend.cancellations.locks shouldContainExactly listOf(orderId, orderId)
            backend.events.published.shouldBeEmpty()
        }

        test("OrderCancelled of an order whose payment is pending voids the pending attempt, without an event") {
            val backend = Backend()
            val pending = backend.stored(attemptFor(chargeRequest(), ProviderDecision.Unreachable))
            val other = backend.stored(attemptFor(chargeRequest(), ProviderDecision.Unreachable))

            val outcome = backend.settleCancelledOrder(cancelled(pending, refundRequired = false, paymentId = null))

            outcome shouldBe CancellationRefund.Voided(listOf(pending.void()))
            backend.attempts.stored[pending.id] shouldBe pending.void()
            backend.attempts.stored[other.id] shouldBe other
            backend.events.published.shouldBeEmpty()
            backend.refunds.stored.values
                .shouldBeEmpty()
            backend.settleCancelledOrder(cancelled(pending, refundRequired = false)) shouldBe
                CancellationRefund.NotRequired
        }

        test("a pending attempt voided meanwhile by another writer is not reported as voided here") {
            val backend = Backend()
            val pending = backend.stored(attemptFor(chargeRequest(), ProviderDecision.Unreachable))
            backend.attempts.voidedElsewhere = true

            backend.settleCancelledOrder(cancelled(pending, refundRequired = false)) shouldBe
                CancellationRefund.NotRequired
        }

        test("OrderCancelled refunds an approval that raced the cancellation, even when no refund was required") {
            val backend = Backend()
            val charge = backend.approvedCharge()
            val pending = backend.stored(attemptFor(chargeRequest(charge.orderId), ProviderDecision.Unreachable))

            val outcome = backend.settleCancelledOrder(cancelled(charge, refundRequired = false, paymentId = null))

            val refund = outcome.shouldBeInstanceOf<CancellationRefund.Refunded>().refund
            refund.attemptId shouldBe charge.id
            refund.idempotencyKey shouldBe IdempotencyKey.refundOf(charge.orderId)
            backend.attempts.stored[pending.id] shouldBe pending.void()
            backend.events.published shouldContainExactly listOf(PaymentEvent.RefundRecorded(refund, ADA_CONTACT))
        }

        test("a pending attempt is voided even when the expected approved charge is missing") {
            val backend = Backend()
            val pending = backend.stored(attemptFor(chargeRequest(), ProviderDecision.Unreachable))

            backend.settleCancelledOrder(cancelled(pending, paymentId = null)) shouldBe
                CancellationRefund.NoApprovedCharge
            backend.attempts.stored[pending.id] shouldBe pending.void()
        }

        test("a refund refused by a concurrent writer is reported, not thrown") {
            val backend = Backend()
            val charge = backend.approvedCharge()
            backend.refunds.concurrent =
                RefundRecord.of(
                    backend.ids.nextRefund(),
                    charge,
                    RefundRequest(charge.orderId, charge.id, charge.amount, newKey()),
                    REFUND_REF,
                    NOW,
                )

            backend.settleCancelledOrder(cancelled(charge)) shouldBe
                CancellationRefund.Refused(PaymentError.AlreadyRefunded)
            backend.events.published.shouldBeEmpty()
        }
    })
