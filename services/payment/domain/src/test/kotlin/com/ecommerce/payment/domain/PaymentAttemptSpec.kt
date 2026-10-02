package com.ecommerce.payment.domain

import arrow.core.left
import arrow.core.right
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.checkAll
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
            PaymentOutcome.fromWire("voided") shouldBe null
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
    })
