package com.ecommerce.payment.application

import arrow.core.left
import arrow.core.right
import com.ecommerce.payment.domain.IdempotencyKey
import com.ecommerce.payment.domain.PaymentAttempt
import com.ecommerce.payment.domain.PaymentError
import com.ecommerce.payment.domain.PaymentEvent
import com.ecommerce.payment.domain.RefundRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

private fun refundOf(
    charge: PaymentAttempt,
    key: IdempotencyKey = newKey(),
): RefundRequest = RefundRequest(charge.orderId, charge.id, charge.amount, key)

/**
 * FR-016 as properties of RecordRefund and the `OrderCancelled` consumer: a refund key answers one request with one
 * refund, a reused key with another body is refused, a charge is refunded once, and however the HTTP refund and the
 * (redelivered) cancellation interleave, an order ends with one refund and one `RefundRecorded`.
 */
class RefundIdempotencyPropertySpec :
    FunSpec({
        test("the same refund key with the same body replays the one refund, without a new provider call or event") {
            checkAll(PROPERTIES, ApplicationArbs.chargeRequest, Arb.int(1..4)) { request, replays ->
                val backend = Backend()
                val charge = backend.authoriseCharge(request).getOrNull()!!.value
                val refundRequest = refundOf(charge)
                val first = backend.recordRefund(refundRequest, ADA_CONTACT).getOrNull()!!

                first.created shouldBe true
                repeat(replays) {
                    backend.recordRefund(refundRequest, ADA_CONTACT) shouldBe
                        Recorded(first.value, created = false).right()
                }
                backend.refunds.stored.values
                    .toList() shouldContainExactly listOf(first.value)
                backend.provider.refunds shouldHaveSize 1
                backend.events.published.filterIsInstance<PaymentEvent.RefundRecorded>() shouldHaveSize 1
            }
        }

        test("the same refund key with another body is a validation error that changes nothing") {
            checkAll(PROPERTIES, ApplicationArbs.chargeRequest, ApplicationArbs.refundDrift) { request, drift ->
                val backend = Backend()
                val charge = backend.authoriseCharge(request).getOrNull()!!.value
                val refundRequest = refundOf(charge)
                val refund = backend.recordRefund(refundRequest).getOrNull()!!.value

                backend.recordRefund(drift(refundRequest)) shouldBe PaymentError.IdempotencyKeyReuse.left()
                backend.refunds.stored.values
                    .toList() shouldContainExactly listOf(refund)
                backend.provider.refunds shouldHaveSize 1
            }
        }

        test("a refunded charge is refused under any other key") {
            checkAll(PROPERTIES, ApplicationArbs.chargeRequest, ApplicationArbs.key) { request, otherKey ->
                val backend = Backend()
                val charge = backend.authoriseCharge(request).getOrNull()!!.value
                backend.recordRefund(refundOf(charge))

                backend.recordRefund(refundOf(charge, otherKey)) shouldBe PaymentError.AlreadyRefunded.left()
                backend.refunds.stored.values shouldHaveSize 1
            }
        }

        test("an HTTP refund and redelivered cancellations converge on one refund and one RefundRecorded") {
            checkAll(PROPERTIES, ApplicationArbs.chargeRequest, Arb.boolean(), Arb.int(1..3), Arb.boolean()) {
                request,
                refundedOverHttp,
                deliveries,
                refundRequired,
                ->
                val backend = Backend()
                val charge = backend.authoriseCharge(request).getOrNull()!!.value
                if (refundedOverHttp) backend.recordRefund(refundOf(charge))

                repeat(deliveries) {
                    backend.settleCancelledOrder(CancelledOrder(charge.orderId, refundRequired, null, ADA_CONTACT))
                }

                val refund =
                    backend.refunds.stored.values
                        .single()
                refund.attemptId shouldBe charge.id
                refund.awaitingAnnouncement shouldBe false
                backend.events.published.filterIsInstance<PaymentEvent.RefundRecorded>() shouldContainExactly
                    listOf(PaymentEvent.RefundRecorded(refund, ADA_CONTACT))
                backend.provider.refunds shouldHaveSize 1
            }
        }
    })
