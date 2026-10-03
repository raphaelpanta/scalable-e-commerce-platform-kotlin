package com.ecommerce.payment.application

import com.ecommerce.payment.domain.ChargeRequest
import com.ecommerce.payment.domain.DeclineCategory
import com.ecommerce.payment.domain.IdempotencyKey
import com.ecommerce.payment.domain.PaymentAttempt
import com.ecommerce.payment.domain.PaymentAttemptId
import com.ecommerce.payment.domain.PaymentEvent
import com.ecommerce.payment.domain.PaymentOutcome
import com.ecommerce.payment.domain.ProviderDecision
import com.ecommerce.payment.domain.RefundRecord
import com.ecommerce.payment.domain.RefundRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.util.UUID

private val DUE: Instant = NOW.plus(POLICY.delay)

/** A backend whose clock reads [at], the delay of the retry policy after the attempts stored at [NOW]. */
private fun retrying(at: Instant = DUE): Backend = Backend(fixedClock(at))

private fun Backend.pending(request: ChargeRequest = chargeRequest()): PaymentAttempt =
    stored(attemptFor(request, ProviderDecision.Unreachable))

/** The single attempt that retries [previous]. */
private fun Backend.retryOf(previous: PaymentAttempt): PaymentAttempt =
    attempts.stored.values.single { it.previousAttemptId == previous.id }

/** RetryPendingCharges and ChargeSettlement: the bounded retry of pending charges and approvals of cancelled orders. */
class PaymentRetriesSpec :
    FunSpec({
        test("a due pending attempt is voided and retried as attempt 2 of the order, with the retry's event") {
            val backend = retrying()
            val pending = backend.pending()

            backend.retryPendingCharges(10) shouldBe 1

            val retry = backend.retryOf(pending)
            backend.attempts.stored[pending.id] shouldBe pending.void()
            retry shouldBe
                PaymentAttempt(
                    backend.ids.attempts.single(),
                    pending.orderId,
                    pending.accountId,
                    pending.amount,
                    pending.paymentMethodRef,
                    PaymentOutcome.APPROVED,
                    null,
                    CHARGE_REF,
                    IdempotencyKey.retryOf(pending.idempotencyKey),
                    DUE,
                    2,
                    pending.id,
                )
            backend.provider.charges shouldContainExactly listOf(pending.paymentMethodRef to pending.amount)
            backend.provider.attemptNumbers shouldContainExactly listOf(2)
            backend.events.published shouldContainExactly listOf(PaymentEvent.ChargeRecorded(retry))
            backend.cancellations.locks shouldContainExactly listOf(pending.orderId, pending.orderId)
        }

        test("an attempt younger than the delay is not retried yet") {
            val backend = retrying(DUE.minusMillis(1))
            backend.pending()

            backend.retryPendingCharges(10) shouldBe 0

            backend.provider.charges.shouldBeEmpty()
            backend.events.published.shouldBeEmpty()
        }

        test("a retry still pending is retried again until the order has its maximum of attempts") {
            val clock = SteppingClock(DUE)
            val backend = Backend(clock)
            backend.provider.decision = ProviderDecision.Unreachable
            val first = backend.pending()

            backend.retryPendingCharges(10) shouldBe 1
            val second = backend.retryOf(first)
            backend.retryPendingCharges(10) shouldBe 0
            clock.advance(POLICY.delay)
            backend.retryPendingCharges(10) shouldBe 1
            val third = backend.retryOf(second)
            clock.advance(POLICY.delay)
            backend.retryPendingCharges(10) shouldBe 0

            listOf(first, second, third).map {
                backend.attempts.stored
                    .getValue(it.id)
                    .outcome
            } shouldContainExactly
                listOf(PaymentOutcome.VOIDED, PaymentOutcome.VOIDED, PaymentOutcome.PENDING)
            third.attemptNumber shouldBe POLICY.maxAttempts
            backend.provider.attemptNumbers shouldContainExactly listOf(2, 3)
            backend.events.published shouldContainExactly
                listOf(PaymentEvent.ChargeRecorded(second), PaymentEvent.ChargeRecorded(third))
        }

        test("a declined retry publishes PaymentDeclined with its category") {
            val backend = retrying()
            backend.provider.decision = ProviderDecision.Declined(DeclineCategory.INSUFFICIENT_FUNDS, CHARGE_REF)
            val pending = backend.pending()

            backend.retryPendingCharges(10)

            val retry = backend.retryOf(pending)
            retry.declineCategory shouldBe DeclineCategory.INSUFFICIENT_FUNDS
            backend.events.published shouldContainExactly listOf(PaymentEvent.ChargeRecorded(retry))
        }

        test("a pass retries at most a batch, oldest first, and reports how many were due") {
            val backend = retrying(NOW.plusSeconds(3600))
            val attempts =
                listOf(2L, 0L, 1L)
                    .map { attemptFor(chargeRequest(), ProviderDecision.Unreachable, NOW.plusSeconds(it)) }
                    .map { backend.stored(it) }
                    .sortedBy { it.createdAt }

            backend.retryPendingCharges(2) shouldBe 2

            attempts.map {
                backend.attempts.stored
                    .getValue(it.id)
                    .outcome
            } shouldContainExactly
                listOf(PaymentOutcome.VOIDED, PaymentOutcome.VOIDED, PaymentOutcome.PENDING)
            backend.retryPendingCharges(2) shouldBe 1
        }

        test("an order approved meanwhile under another key gets its stale attempt voided, not charged again") {
            val backend = retrying()
            val pending = backend.pending()
            backend.approvedCharge(pending.orderId)

            backend.retryPendingCharges(10) shouldBe 1

            backend.attempts.stored[pending.id] shouldBe pending.void()
            backend.provider.charges.shouldBeEmpty()
            backend.events.published.shouldBeEmpty()
            backend.retryPendingCharges.retry(pending) shouldBe PendingRetry.Settled
        }

        test("an attempt voided meanwhile (cancelled order, other instance) is left alone") {
            val backend = retrying()
            val pending = backend.pending()
            backend.attempts.voidedElsewhere = true

            backend.retryPendingCharges.retry(pending) shouldBe PendingRetry.Settled

            backend.attempts.stored.values shouldHaveSize 1
            backend.events.published.shouldBeEmpty()

            val approvedOrder = retrying()
            val stale = approvedOrder.pending()
            approvedOrder.approvedCharge(stale.orderId)
            approvedOrder.attempts.voidedElsewhere = true
            approvedOrder.retryPendingCharges.retry(stale) shouldBe PendingRetry.Settled
        }

        test("a retry that cannot be stored rolls the voiding back with an error") {
            val backend = retrying()
            val pending = backend.pending()
            backend.attempts.concurrent =
                pending.retry(PaymentAttemptId(UUID.randomUUID()), ProviderDecision.Unreachable, NOW)

            shouldThrow<IllegalStateException> { backend.retryPendingCharges.retry(pending) }
        }

        test("a retry approved for an order cancelled meanwhile is refunded at once, with RefundRecorded") {
            val backend = retrying()
            val pending = backend.pending()
            backend.cancelled(pending.orderId)

            val outcome = backend.retryPendingCharges.retry(pending).shouldBeInstanceOf<PendingRetry.Retried>()

            outcome.voided shouldBe pending.void()
            val retry = outcome.retry
            retry.outcome shouldBe PaymentOutcome.APPROVED
            val refund =
                backend.refunds.stored.values
                    .single()
            refund.attemptId shouldBe retry.id
            refund.idempotencyKey shouldBe IdempotencyKey.refundOf(pending.orderId)
            refund.announcedAt shouldBe DUE
            backend.events.published shouldContainExactly
                listOf(PaymentEvent.ChargeRecorded(retry), PaymentEvent.RefundRecorded(refund, ADA_CONTACT))
        }

        test("a retry still pending for an order cancelled meanwhile is stored voided, without an event") {
            val backend = retrying()
            backend.provider.decision = ProviderDecision.Unreachable
            val pending = backend.pending()
            backend.cancelled(pending.orderId)

            val retry =
                backend.retryPendingCharges
                    .retry(pending)
                    .shouldBeInstanceOf<PendingRetry.Retried>()
                    .retry

            retry.outcome shouldBe PaymentOutcome.VOIDED
            backend.attempts.stored[retry.id] shouldBe retry
            backend.events.published.shouldBeEmpty()
        }

        test("a charge approved after the order's cancellation is recorded, published and refunded at once") {
            val backend = Backend()
            val request = chargeRequest()
            backend.cancelled(request.orderId)

            val attempt = backend.chargePlacedOrder(request).shouldBeInstanceOf<PlacedOrderCharge.Charged>().attempt

            attempt.outcome shouldBe PaymentOutcome.APPROVED
            val refund =
                backend.refunds.stored.values
                    .single()
            refund.attemptId shouldBe attempt.id
            refund.amount shouldBe request.amount
            backend.provider.refunds shouldContainExactly listOf(CHARGE_REF to request.amount)
            backend.events.published shouldContainExactly
                listOf(PaymentEvent.ChargeRecorded(attempt), PaymentEvent.RefundRecorded(refund, ADA_CONTACT))
            backend.chargePlacedOrder(request) shouldBe PlacedOrderCharge.Converged(attempt)
            backend.refunds.stored.values shouldHaveSize 1
        }

        test("a charge of a cancelled order that stays pending is voided; a declined one is only published") {
            val pendingBackend = Backend().apply { provider.decision = ProviderDecision.Unreachable }
            val pendingRequest = chargeRequest()
            pendingBackend.cancelled(pendingRequest.orderId)
            val voided = pendingBackend.authoriseCharge(pendingRequest).getOrNull()!!.value
            voided.outcome shouldBe PaymentOutcome.VOIDED
            pendingBackend.events.published.shouldBeEmpty()

            val declinedBackend =
                Backend().apply {
                    provider.decision = ProviderDecision.Declined(DeclineCategory.CARD_REJECTED, CHARGE_REF)
                }
            val declinedRequest = chargeRequest()
            declinedBackend.cancelled(declinedRequest.orderId)
            val declined = declinedBackend.authoriseCharge(declinedRequest).getOrNull()!!.value
            declinedBackend.events.published shouldContainExactly listOf(PaymentEvent.ChargeRecorded(declined))
            declinedBackend.refunds.stored.values
                .shouldBeEmpty()
        }

        test("a charge of an order that is not cancelled is never refunded") {
            val backend = Backend()
            backend.cancelled(newOrder())

            backend.authoriseCharge(chargeRequest())

            backend.refunds.stored.values
                .shouldBeEmpty()
            backend.events.published shouldHaveSize 1
        }

        test("a late approval whose refund is refused fails the transaction instead of keeping the money") {
            val backend = Backend()
            val request = chargeRequest()
            backend.cancelled(request.orderId)
            val other = attemptFor(chargeRequest())
            backend.refunds.concurrent =
                RefundRecord.of(
                    backend.ids.nextRefund(),
                    other,
                    RefundRequest(other.orderId, other.id, other.amount, IdempotencyKey.refundOf(request.orderId)),
                    REFUND_REF,
                    NOW,
                )

            shouldThrow<IllegalStateException> { backend.authoriseCharge(request) }
        }
    })
