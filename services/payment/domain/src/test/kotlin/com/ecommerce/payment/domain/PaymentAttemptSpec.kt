package com.ecommerce.payment.domain

import arrow.core.left
import arrow.core.right
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.checkAll
import java.time.Duration
import java.util.UUID

private val CHARGE_REF = reference("sim_ch_000123")
private val REFUND_REF = reference("sim_rf_000045")

private fun refundOf(attempt: PaymentAttempt): RefundRequest =
    RefundRequest(attempt.orderId, attempt.id, attempt.amount, PaymentArbs.key.one())

/** Attempt invariants (data-model section 3.5), idempotent replay and the refund preconditions. */
class PaymentAttemptSpec :
    FunSpec({
        test("an attempt records the provider's decision: category only when declined, no reference while pending") {
            checkAll(PaymentArbs.attemptId, PaymentArbs.chargeRequest, PaymentArbs.decision) { id, request, decision ->
                val attempt = PaymentAttempt.charge(id, request, decision, NOW)
                attempt.id shouldBe id
                attempt.orderId shouldBe request.orderId
                attempt.accountId shouldBe request.accountId
                attempt.amount shouldBe request.amount
                attempt.paymentMethodRef shouldBe request.paymentMethodRef
                attempt.idempotencyKey shouldBe request.idempotencyKey
                attempt.createdAt shouldBe NOW
                attempt.kind shouldBe AttemptKind.CHARGE
                attempt.outcome shouldBe decision.outcome
                attempt.providerReference shouldBe decision.reference
                attempt.declineCategory shouldBe (decision as? ProviderDecision.Declined)?.category
            }
        }

        test("the outcome, decline category and provider reference must agree") {
            val approved = PaymentArbs.approvedAttempt.one()
            shouldThrow<IllegalArgumentException> { approved.copy(declineCategory = DeclineCategory.CARD_REJECTED) }
            shouldThrow<IllegalArgumentException> { approved.copy(providerReference = null) }
            shouldThrow<IllegalArgumentException> { approved.copy(outcome = PaymentOutcome.DECLINED) }
            shouldThrow<IllegalArgumentException> { approved.copy(outcome = PaymentOutcome.PENDING) }
            shouldThrow<IllegalArgumentException> {
                approved.copy(
                    outcome = PaymentOutcome.PENDING,
                    providerReference = null,
                    declineCategory = DeclineCategory.CARD_EXPIRED,
                )
            }
            shouldThrow<IllegalArgumentException> { approved.copy(amount = money(0)) }
            approved.copy(outcome = PaymentOutcome.PENDING, providerReference = null).outcome shouldBe
                PaymentOutcome.PENDING
            approved
                .copy(
                    outcome = PaymentOutcome.DECLINED,
                    declineCategory = DeclineCategory.CARD_EXPIRED,
                ).outcome shouldBe
                PaymentOutcome.DECLINED
            approved.copy(amount = money(1)).amount shouldBe money(1)
        }

        test("a charge request is strictly positive") {
            val request = PaymentArbs.chargeRequest.one()
            shouldThrow<IllegalArgumentException> { request.copy(amount = money(0)) }
            request.copy(amount = money(1)).amount shouldBe money(1)
        }

        test("the same key with the same body replays the attempt; any other body is a key reuse") {
            checkAll(PaymentArbs.attempt) { attempt ->
                val same =
                    ChargeRequest(
                        attempt.orderId,
                        attempt.accountId,
                        attempt.amount,
                        attempt.paymentMethodRef,
                        attempt.idempotencyKey,
                    )
                attempt.replay(same) shouldBe attempt.right()
                attempt.answers(same) shouldBe true
                listOf(
                    same.copy(orderId = OrderId(UUID.randomUUID())),
                    same.copy(accountId = AccountId(UUID.randomUUID())),
                    same.copy(amount = money(attempt.amount.amountMinor + 1)),
                    same.copy(paymentMethodRef = token(attempt.paymentMethodRef.token + "x")),
                    same.copy(idempotencyKey = IdempotencyKey(UUID.randomUUID())),
                ).forEach { drifted ->
                    attempt.answers(drifted) shouldBe false
                    attempt.replay(drifted) shouldBe PaymentError.IdempotencyKeyReuse.left()
                }
            }
        }

        test("only the full amount of an approved charge of the named order can be refunded") {
            checkAll(PaymentArbs.approvedAttempt) { charge ->
                val request = refundOf(charge)
                charge.refundable(request) shouldBe charge.right()
                charge.refundable(request.copy(orderId = OrderId(UUID.randomUUID()))) shouldBe
                    PaymentError.AttemptNotFound.left()
                charge.refundable(request.copy(attemptId = PaymentAttemptId(UUID.randomUUID()))) shouldBe
                    PaymentError.AttemptNotFound.left()
                charge.refundable(request.copy(amount = money(charge.amount.amountMinor + 1))) shouldBe
                    PaymentError.Invalid("amount", "must equal the charged amount").left()
            }
            val approved = PaymentArbs.approvedAttempt.one()
            val declined =
                approved.copy(
                    outcome = PaymentOutcome.DECLINED,
                    declineCategory = DeclineCategory.CARD_REJECTED,
                )
            val pending = approved.copy(outcome = PaymentOutcome.PENDING, providerReference = null)
            declined.refundable(refundOf(declined)) shouldBe PaymentError.NotRefundable.left()
            pending.refundable(refundOf(pending).copy(amount = money(1))) shouldBe PaymentError.NotRefundable.left()
        }

        test("an approved charge belongs to its order") {
            checkAll(PaymentArbs.approvedAttempt) { charge ->
                charge.isApprovedChargeOf(charge.orderId) shouldBe true
                charge.isApprovedChargeOf(OrderId(UUID.randomUUID())) shouldBe false
                charge
                    .copy(outcome = PaymentOutcome.PENDING, providerReference = null)
                    .isApprovedChargeOf(charge.orderId) shouldBe false
            }
        }

        test("at most one approved charge per order") {
            ensureNotCharged(null) shouldBe Unit.right()
            ensureNotCharged(PaymentArbs.approvedAttempt.one()) shouldBe PaymentError.AlreadyCharged.left()
        }

        test("a refund copies the charge and keeps its own key; a replay needs the same body") {
            checkAll(PaymentArbs.approvedAttempt, PaymentArbs.refundId) { charge, id ->
                val request = refundOf(charge)
                val refund = RefundRecord.of(id, charge, request, REFUND_REF, NOW)
                refund.id shouldBe id
                refund.orderId shouldBe charge.orderId
                refund.accountId shouldBe charge.accountId
                refund.attemptId shouldBe charge.id
                refund.amount shouldBe charge.amount
                refund.providerReference shouldBe REFUND_REF
                refund.idempotencyKey shouldBe request.idempotencyKey
                refund.createdAt shouldBe NOW
                refund.status shouldBe RefundStatus.RECORDED
                refund.awaitingAnnouncement shouldBe true
                refund.copy(announcedAt = NOW).awaitingAnnouncement shouldBe false
                refund.replay(request) shouldBe refund.right()
                listOf(
                    request.copy(orderId = OrderId(UUID.randomUUID())),
                    request.copy(attemptId = PaymentAttemptId(UUID.randomUUID())),
                    request.copy(amount = money(charge.amount.amountMinor + 1)),
                    request.copy(idempotencyKey = IdempotencyKey(UUID.randomUUID())),
                ).forEach { drifted ->
                    refund.answers(drifted) shouldBe false
                    refund.replay(drifted) shouldBe PaymentError.IdempotencyKeyReuse.left()
                }
            }
        }

        test("one refund per charge, and refunds are strictly positive") {
            val charge = PaymentArbs.approvedAttempt.one()
            val refund = RefundRecord.of(PaymentArbs.refundId.one(), charge, refundOf(charge), REFUND_REF, NOW)
            ensureNotRefunded(null) shouldBe Unit.right()
            ensureNotRefunded(refund) shouldBe PaymentError.AlreadyRefunded.left()
            shouldThrow<IllegalArgumentException> { refund.copy(amount = money(0)) }
            refund.copy(amount = money(1)).amount shouldBe money(1)
        }

        test("wire names and identifiers") {
            checkAll(Arb.enum<DeclineCategory>()) { DeclineCategory.fromWire(it.wire) shouldBe it }
            checkAll(Arb.enum<PaymentOutcome>()) { PaymentOutcome.fromWire(it.wire) shouldBe it }
            DeclineCategory.fromWire("method_rejected") shouldBe null
            PaymentOutcome.fromWire("refunded") shouldBe null
            PaymentOutcome.entries.map { it.wire } shouldBe listOf("approved", "declined", "pending", "voided")
            DeclineCategory.entries.map { it.wire } shouldBe
                listOf(
                    "insufficient_funds",
                    "card_expired",
                    "card_rejected",
                    "suspected_fraud",
                    "invalid_payment_method",
                )
            AttemptKind.REFUND.wire shouldBe "refund"
            AttemptKind.CHARGE.wire shouldBe "charge"
            RefundStatus.RECORDED.wire shouldBe "recorded"
            val uuid = UUID.randomUUID()
            listOf(
                PaymentAttemptId(uuid).toString(),
                RefundId(uuid).toString(),
                OrderId(uuid).toString(),
                AccountId(uuid).toString(),
                IdempotencyKey(uuid).toString(),
            ).forEach { it shouldBe uuid.toString() }
            CHARGE_REF.toString() shouldBe "sim_ch_000123"
        }

        test("the refund key of a cancelled order is derived from the order alone") {
            checkAll(PaymentArbs.orderId, PaymentArbs.orderId) { first, second ->
                IdempotencyKey.refundOf(first) shouldBe IdempotencyKey.refundOf(OrderId(first.value))
                (IdempotencyKey.refundOf(first) == IdempotencyKey.refundOf(second)) shouldBe (first == second)
            }
            IdempotencyKey.refundOf(OrderId(UUID.fromString("0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10"))).value shouldBe
                UUID.nameUUIDFromBytes("payment:refund-of-order:0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10".toByteArray())
        }

        test("the key of a retry is derived from the key of the attempt it retries, never equal to it") {
            checkAll(PaymentArbs.key, PaymentArbs.key) { first, second ->
                IdempotencyKey.retryOf(first) shouldBe IdempotencyKey.retryOf(IdempotencyKey(first.value))
                (IdempotencyKey.retryOf(first) == IdempotencyKey.retryOf(second)) shouldBe (first == second)
                (IdempotencyKey.retryOf(first) == first) shouldBe false
            }
            val checkout = IdempotencyKey(UUID.fromString("6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f"))
            IdempotencyKey.retryOf(checkout).value shouldBe
                UUID.nameUUIDFromBytes("payment:retry-of:6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f".toByteArray())
        }

        test("a pending attempt is voided without a reference; any other attempt cannot be voided") {
            checkAll(PaymentArbs.pendingAttempt) { pending ->
                pending.isPending shouldBe true
                val voided = pending.void()
                voided shouldBe pending.copy(outcome = PaymentOutcome.VOIDED)
                voided.isPending shouldBe false
                voided.providerReference shouldBe null
                shouldThrow<IllegalStateException> { voided.void() }
            }
            val approved = PaymentArbs.approvedAttempt.one()
            approved.isPending shouldBe false
            shouldThrow<IllegalStateException> { approved.void() }
            shouldThrow<IllegalArgumentException> { approved.copy(outcome = PaymentOutcome.VOIDED) }
        }

        test("a retry is attempt N + 1 of the same order, linked to the attempt it retries, under a derived key") {
            checkAll(PaymentArbs.pendingAttempt, PaymentArbs.attemptId, PaymentArbs.decision) { pending, id, decision ->
                val later = NOW.plusSeconds(60)
                val retry = pending.retry(id, decision, later)
                retry.id shouldBe id
                retry.orderId shouldBe pending.orderId
                retry.accountId shouldBe pending.accountId
                retry.amount shouldBe pending.amount
                retry.paymentMethodRef shouldBe pending.paymentMethodRef
                retry.outcome shouldBe decision.outcome
                retry.providerReference shouldBe decision.reference
                retry.declineCategory shouldBe (decision as? ProviderDecision.Declined)?.category
                retry.idempotencyKey shouldBe IdempotencyKey.retryOf(pending.idempotencyKey)
                retry.createdAt shouldBe later
                retry.attemptNumber shouldBe 2
                retry.previousAttemptId shouldBe pending.id
                val third = retry.takeIf { it.isPending }?.retry(PaymentArbs.attemptId.one(), decision, later)
                third?.attemptNumber?.let { it shouldBe 3 }
                third?.previousAttemptId?.let { it shouldBe retry.id }
            }
            shouldThrow<IllegalStateException> {
                PaymentArbs.approvedAttempt.one().retry(PaymentArbs.attemptId.one(), ProviderDecision.Unreachable, NOW)
            }
        }

        test("only the first attempt has no previous one, and attempts are numbered from 1") {
            val pending = PaymentArbs.pendingAttempt.one()
            val retry = pending.retry(PaymentArbs.attemptId.one(), ProviderDecision.Unreachable, NOW)
            pending.attemptNumber shouldBe 1
            pending.previousAttemptId shouldBe null
            shouldThrow<IllegalArgumentException> { pending.copy(attemptNumber = 0, previousAttemptId = null) }
            shouldThrow<IllegalArgumentException> { pending.copy(attemptNumber = 2) }
            shouldThrow<IllegalArgumentException> { retry.copy(attemptNumber = 1) }
            shouldThrow<IllegalArgumentException> { retry.copy(previousAttemptId = null) }
            retry.copy(attemptNumber = 3).attemptNumber shouldBe 3
        }

        test("a pending attempt is due for a retry once old enough and while below the maximum of attempts") {
            val policy = RetryPolicy(Duration.ofSeconds(60), 3)
            val pending = PaymentArbs.pendingAttempt.one()
            policy.cutoff(NOW.plusSeconds(60)) shouldBe NOW
            policy.isDue(pending, NOW.plusSeconds(60)) shouldBe true
            policy.isDue(pending, NOW.plusSeconds(59)) shouldBe false
            policy.isDue(pending, NOW.plusSeconds(600)) shouldBe true
            val second = pending.retry(PaymentArbs.attemptId.one(), ProviderDecision.Unreachable, NOW)
            policy.isDue(second, NOW.plusSeconds(60)) shouldBe true
            val third = second.retry(PaymentArbs.attemptId.one(), ProviderDecision.Unreachable, NOW)
            policy.isDue(third, NOW.plusSeconds(600)) shouldBe false
            policy.isDue(pending.void(), NOW.plusSeconds(600)) shouldBe false
            policy.isDue(PaymentArbs.approvedAttempt.one(), NOW.plusSeconds(600)) shouldBe false
            RetryPolicy(Duration.ZERO, 1).isDue(pending, NOW) shouldBe false
            RetryPolicy(Duration.ZERO, 2).isDue(pending, NOW) shouldBe true
            shouldThrow<IllegalArgumentException> { RetryPolicy(Duration.ofSeconds(-1), 3) }
            shouldThrow<IllegalArgumentException> { RetryPolicy(Duration.ZERO, 0) }
        }
    })
