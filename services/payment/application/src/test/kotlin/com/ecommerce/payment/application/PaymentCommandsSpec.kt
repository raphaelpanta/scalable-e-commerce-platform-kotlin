package com.ecommerce.payment.application

import arrow.core.left
import arrow.core.right
import com.ecommerce.payment.domain.DeclineCategory
import com.ecommerce.payment.domain.PaymentAttemptId
import com.ecommerce.payment.domain.PaymentError
import com.ecommerce.payment.domain.PaymentEvent
import com.ecommerce.payment.domain.PaymentOutcome
import com.ecommerce.payment.domain.ProviderDecision
import com.ecommerce.payment.domain.RefundRecord
import com.ecommerce.payment.domain.RefundRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.util.UUID

private fun refundRequest(
    charge: com.ecommerce.payment.domain.PaymentAttempt,
    key: com.ecommerce.payment.domain.IdempotencyKey = newKey(),
): RefundRequest = RefundRequest(charge.orderId, charge.id, charge.amount, key)

/** AuthoriseCharge and RecordRefund with fake ports: idempotent replay, key reuse, conflicts and events. */
class PaymentCommandsSpec :
    FunSpec({
        test("a new key charges through the provider and stores the attempt with its event, in one transaction") {
            listOf(
                ProviderDecision.Approved(CHARGE_REF),
                ProviderDecision.Declined(DeclineCategory.INSUFFICIENT_FUNDS, CHARGE_REF),
                ProviderDecision.Unreachable,
            ).forEach { decision ->
                val backend = Backend().apply { provider.decision = decision }
                val request = chargeRequest(amountMinor = 4_913)

                val result = backend.authoriseCharge(request).getOrNull()!!

                result.created shouldBe true
                val attempt = result.value
                attempt.id shouldBe backend.ids.attempts.single()
                attempt.outcome shouldBe decision.outcome
                attempt.providerReference shouldBe decision.reference
                attempt.createdAt shouldBe NOW
                backend.attempts.stored.values
                    .toList() shouldContainExactly listOf(attempt)
                backend.events.published shouldContainExactly listOf(PaymentEvent.ChargeRecorded(attempt))
                backend.provider.charges shouldContainExactly listOf(request.paymentMethodRef to request.amount)
                backend.transactions.count shouldBe 1
            }
        }

        test("the same key with the same body replays the stored attempt without calling the provider again") {
            val backend = Backend()
            val request = chargeRequest()
            val first = backend.authoriseCharge(request).getOrNull()!!.value
            backend.provider.decision = ProviderDecision.Unreachable

            backend.authoriseCharge(request) shouldBe Recorded(first, created = false).right()

            backend.provider.charges shouldHaveSize 1
            backend.events.published shouldHaveSize 1
            backend.attempts.stored.values shouldHaveSize 1
        }

        test("the same key with a different body is refused and changes nothing") {
            val backend = Backend()
            val request = chargeRequest()
            backend.authoriseCharge(request)

            backend.authoriseCharge(request.copy(amount = brl(1))) shouldBe PaymentError.IdempotencyKeyReuse.left()

            backend.provider.charges shouldHaveSize 1
            backend.attempts.stored.values shouldHaveSize 1
        }

        test("a new key for an order with an approved charge is a conflict; after a decline or pending it charges") {
            val backend = Backend()
            val charge = backend.approvedCharge()

            backend.authoriseCharge(chargeRequest(charge.orderId)) shouldBe PaymentError.AlreadyCharged.left()
            backend.provider.charges.shouldBeEmpty()

            val pending = backend.stored(attemptFor(chargeRequest(), ProviderDecision.Unreachable))
            val retry = backend.authoriseCharge(chargeRequest(pending.orderId)).getOrNull()!!
            retry.created shouldBe true
            backend.attempts.stored.values
                .filter { it.orderId == pending.orderId } shouldHaveSize 2
        }

        test("a concurrent request that stored the same key first is replayed; a different body is a reuse") {
            val backend = Backend()
            val request = chargeRequest()
            val winner = attemptFor(request, ProviderDecision.Declined(DeclineCategory.CARD_REJECTED, CHARGE_REF))
            backend.attempts.concurrent = winner

            backend.authoriseCharge(request) shouldBe Recorded(winner, created = false).right()
            backend.events.published.shouldBeEmpty()

            val other = Backend()
            other.attempts.concurrent = attemptFor(request.copy(amount = brl(1)))
            other.authoriseCharge(request) shouldBe PaymentError.IdempotencyKeyReuse.left()
        }

        test("a concurrent approval of the same order under another key wins: the loser is a conflict") {
            val backend = Backend()
            val request = chargeRequest()
            backend.attempts.concurrent = attemptFor(chargeRequest(request.orderId))

            backend.authoriseCharge(request) shouldBe PaymentError.AlreadyCharged.left()
            backend.events.published.shouldBeEmpty()
        }

        test("an approved charge is refunded in full, with RefundRecorded when the owner's contact is known") {
            val backend = Backend()
            val charge = backend.approvedCharge()
            val request = refundRequest(charge)

            val result = backend.recordRefund(request, ADA_CONTACT).getOrNull()!!

            result.created shouldBe true
            val refund = result.value
            refund shouldBe
                RefundRecord(
                    backend.ids.refunds.single(),
                    charge.orderId,
                    ADA,
                    charge.id,
                    charge.amount,
                    REFUND_REF,
                    request.idempotencyKey,
                    NOW,
                    NOW,
                )
            backend.provider.refunds shouldContainExactly listOf(CHARGE_REF to charge.amount)
            backend.events.published shouldContainExactly listOf(PaymentEvent.RefundRecorded(refund, ADA_CONTACT))
            backend.refunds.stored.values
                .toList() shouldContainExactly listOf(refund)
            backend.transactions.count shouldBe 1
        }

        test("without the owner's contact the refund is recorded and its event waits") {
            val backend = Backend()
            val charge = backend.approvedCharge()

            val refund = backend.recordRefund(refundRequest(charge)).getOrNull()!!.value

            refund.announcedAt shouldBe null
            backend.refunds.stored.values
                .toList() shouldContainExactly listOf(refund)
            backend.events.published.shouldBeEmpty()
        }

        test("a refund replays under its key and refuses a different body") {
            val backend = Backend()
            val charge = backend.approvedCharge()
            val request = refundRequest(charge)
            val refund = backend.recordRefund(request, ADA_CONTACT).getOrNull()!!.value

            backend.recordRefund(request, ADA_CONTACT) shouldBe Recorded(refund, created = false).right()
            backend.recordRefund(request.copy(amount = brl(1))) shouldBe PaymentError.IdempotencyKeyReuse.left()
            backend.provider.refunds shouldHaveSize 1
            backend.events.published shouldHaveSize 1
        }

        test("refunds need a known attempt of the order, approved, for its full amount, refunded once") {
            val backend = Backend()
            val charge = backend.approvedCharge()
            val declined =
                backend.stored(
                    attemptFor(chargeRequest(), ProviderDecision.Declined(DeclineCategory.CARD_EXPIRED, CHARGE_REF)),
                )

            backend.recordRefund(refundRequest(charge).copy(attemptId = PaymentAttemptId(UUID.randomUUID()))) shouldBe
                PaymentError.AttemptNotFound.left()
            backend.recordRefund(refundRequest(charge).copy(orderId = newOrder())) shouldBe
                PaymentError.AttemptNotFound.left()
            backend.recordRefund(refundRequest(declined)) shouldBe PaymentError.NotRefundable.left()
            backend.recordRefund(refundRequest(charge).copy(amount = brl(1))) shouldBe
                PaymentError.Invalid("amount", "must equal the charged amount").left()
            backend.recordRefund(refundRequest(charge)).isRight() shouldBe true
            backend.recordRefund(refundRequest(charge)) shouldBe PaymentError.AlreadyRefunded.left()
            backend.provider.refunds shouldHaveSize 1
        }

        test("a concurrent refund of the same key is replayed; of the same charge under another key, a conflict") {
            val backend = Backend()
            val charge = backend.approvedCharge()
            val request = refundRequest(charge)
            val winner = RefundRecord.of(backend.ids.nextRefund(), charge, request, REFUND_REF, NOW)
            backend.refunds.concurrent = winner

            backend.recordRefund(request, ADA_CONTACT) shouldBe Recorded(winner, created = false).right()
            backend.events.published.shouldBeEmpty()

            val other = Backend()
            val otherCharge = other.approvedCharge()
            other.refunds.concurrent =
                RefundRecord.of(other.ids.nextRefund(), otherCharge, refundRequest(otherCharge), REFUND_REF, NOW)
            other.recordRefund(refundRequest(otherCharge), ADA_CONTACT) shouldBe PaymentError.AlreadyRefunded.left()
        }

        test("a pending attempt has no provider reference and cannot be refunded") {
            val backend = Backend()
            val pending = backend.stored(attemptFor(chargeRequest(), ProviderDecision.Unreachable))
            pending.outcome shouldBe PaymentOutcome.PENDING
            backend.recordRefund(refundRequest(pending)) shouldBe PaymentError.NotRefundable.left()
        }
    })
