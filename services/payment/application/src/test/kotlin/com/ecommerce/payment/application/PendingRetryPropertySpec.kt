package com.ecommerce.payment.application

import com.ecommerce.payment.domain.OrderId
import com.ecommerce.payment.domain.PaymentEvent
import com.ecommerce.payment.domain.PaymentOutcome
import com.ecommerce.payment.domain.ProviderDecision
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll

/**
 * US4 scenario 7 and data-model section 3.5 as properties: whatever the provider answers to the retries of a pending
 * charge, and wherever the order's cancellation falls among the retry passes, the attempts of an order form one chain
 * numbered 1..n (n at most the policy's maximum) in which every attempt but the last is voided, at most one attempt is
 * pending and at most one approved, a cancelled order keeps nothing pending, and an approval of a cancelled order is
 * refunded exactly once.
 */
class PendingRetryPropertySpec :
    FunSpec({
        test("retries and a cancellation in any order keep one chain, one approval at most, refunded if cancelled") {
            checkAll(
                PROPERTIES,
                ApplicationArbs.chargeRequest,
                Arb.list(ApplicationArbs.decision, 1..5),
                Arb.int(0..6),
            ) { request, decisions, cancelAfter ->
                val clock = SteppingClock()
                val backend = Backend(clock)
                backend.provider.decision = ProviderDecision.Unreachable
                backend.authoriseCharge(request)
                decisions.forEachIndexed { pass, decision ->
                    if (pass == cancelAfter) cancel(backend, request.orderId)
                    clock.advance(POLICY.delay)
                    backend.provider.decision = decision
                    backend.retryPendingCharges(10)
                }
                if (cancelAfter >= decisions.size) cancel(backend, request.orderId)

                val chain =
                    backend.attempts.stored.values
                        .sortedBy { it.attemptNumber }
                chain.map { it.attemptNumber } shouldContainExactly (1..chain.size).toList()
                chain.size shouldBeLessThanOrEqual POLICY.maxAttempts
                chain.zipWithNext().forEach { (previous, next) ->
                    next.previousAttemptId shouldBe previous.id
                    previous.outcome shouldBe PaymentOutcome.VOIDED
                }
                chain.count { it.outcome == PaymentOutcome.APPROVED } shouldBeLessThanOrEqual 1
                chain.filter { it.isPending }.shouldBeEmpty()
                val approved = chain.singleOrNull { it.outcome == PaymentOutcome.APPROVED }
                val refunds = backend.refunds.stored.values
                if (approved == null) {
                    refunds.shouldBeEmpty()
                } else {
                    refunds.single().attemptId shouldBe approved.id
                }
                backend.events.published.filterIsInstance<PaymentEvent.RefundRecorded>() shouldHaveSize refunds.size
                val published = backend.events.published.filterIsInstance<PaymentEvent.ChargeRecorded>()
                published.map { it.attempt.id } shouldContainExactly chain.map { it.id }
                published.forEach { (it.attempt.outcome == PaymentOutcome.VOIDED) shouldBe false }
            }
        }
    })

private suspend fun cancel(
    backend: Backend,
    orderId: OrderId,
) {
    backend.settleCancelledOrder(CancelledOrder(orderId, false, null, ADA_CONTACT))
}
