package com.ecommerce.payment.infrastructure

import arrow.core.getOrElse
import com.ecommerce.payment.domain.AccountId
import com.ecommerce.payment.domain.DeclineCategory
import com.ecommerce.payment.domain.IdempotencyKey
import com.ecommerce.payment.domain.Money
import com.ecommerce.payment.domain.OrderId
import com.ecommerce.payment.domain.PaymentAttempt
import com.ecommerce.payment.domain.PaymentAttemptId
import com.ecommerce.payment.domain.PaymentEvent
import com.ecommerce.payment.domain.PaymentMethodRef
import com.ecommerce.payment.domain.PaymentOutcome
import com.ecommerce.payment.domain.ProviderDecision
import com.ecommerce.payment.domain.ProviderReference
import com.ecommerce.payment.domain.Recipient
import com.ecommerce.payment.domain.RefundId
import com.ecommerce.payment.domain.RefundRecord
import com.ecommerce.payment.domain.SimulatedPaymentRules
import com.ecommerce.payment.infrastructure.messaging.PaymentEnvelopes
import com.ecommerce.payment.infrastructure.provider.SimulatedPaymentProvider
import com.ecommerce.platform.messaging.envelope.EnvelopeFactory
import com.ecommerce.platform.messaging.envelope.EventType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private const val TOTAL = 19_800L
private val AT: Instant = Instant.parse("2026-10-02T10:15:01Z")
private val ADA = AccountId(UUID.fromString("7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"))

private fun token(value: String): PaymentMethodRef = PaymentMethodRef.of(value).getOrElse { error(it) }

private fun attempt(decision: ProviderDecision): PaymentAttempt =
    PaymentAttempt(
        PaymentAttemptId(UUID.randomUUID()),
        OrderId(UUID.randomUUID()),
        ADA,
        Money(TOTAL, "BRL"),
        token("tok_sim_approve_4242"),
        decision.outcome,
        (decision as? ProviderDecision.Declined)?.category,
        decision.reference,
        IdempotencyKey(UUID.randomUUID()),
        AT,
    )

/** The simulated provider adapter and the mapping of payment events to envelopes (events.yaml). */
class PaymentAdaptersTest :
    FunSpec({
        val provider = SimulatedPaymentProvider(SimulatedPaymentRules.DOCUMENT)

        test("the simulated provider follows the rule document and issues sim_ references") {
            val approved = provider.charge(token("tok_sim_approve_4242"), Money(TOTAL, "BRL"), 1)
            approved.outcome shouldBe PaymentOutcome.APPROVED
            approved.reference?.value.orEmpty() shouldStartWith SimulatedPaymentProvider.CHARGE_PREFIX
            val declined = provider.charge(token("tok_sim_approve_4242"), Money(4_913, "BRL"), 1)
            (declined as ProviderDecision.Declined).category shouldBe DeclineCategory.INSUFFICIENT_FUNDS
            provider.charge(token("tok_sim_unreachable"), Money(TOTAL, "BRL"), 1) shouldBe ProviderDecision.Unreachable
            provider.refund(ProviderReference("sim_ch_1"), Money(TOTAL, "BRL")).value shouldStartWith
                SimulatedPaymentProvider.REFUND_PREFIX
            SimulatedPaymentProvider(SimulatedPaymentRules.DOCUMENT) { "000123" }
                .charge(token("tok_sim_decline_0001"), Money(TOTAL, "BRL"), 1) shouldBe
                ProviderDecision.Declined(DeclineCategory.CARD_REJECTED, ProviderReference("sim_ch_000123"))
        }

        test("a retry of an unreachable-token charge is approved; the unreachable-forever token never is") {
            provider.charge(token("tok_sim_unreachable"), Money(TOTAL, "BRL"), 2).outcome shouldBe
                PaymentOutcome.APPROVED
            listOf(1, 2, 3).forEach {
                provider.charge(token("tok_sim_unreachable_forever"), Money(TOTAL, "BRL"), it) shouldBe
                    ProviderDecision.Unreachable
            }
        }

        test("charge outcomes map to PaymentApproved, PaymentDeclined and PaymentPending keyed by the attempt") {
            val envelopes = EnvelopeFactory("payment", Clock.fixed(AT, ZoneOffset.UTC))
            val approved = attempt(ProviderDecision.Approved(ProviderReference("sim_ch_000123")))
            val declined =
                attempt(ProviderDecision.Declined(DeclineCategory.CARD_EXPIRED, ProviderReference("sim_ch_9")))
            val pending = attempt(ProviderDecision.Unreachable)

            val envelope = PaymentEnvelopes.envelopeOf(PaymentEvent.ChargeRecorded(approved), "corr-1", envelopes)
            envelope.type shouldBe EventType.PaymentApproved.name
            envelope.aggregateId shouldBe approved.id.value
            envelope.correlationId shouldBe "corr-1"
            envelope.producer shouldBe "payment"
            envelope.occurredAt shouldBe AT
            val payload = PaymentEnvelopes.payloadOf(PaymentEvent.ChargeRecorded(approved))
            payload["paymentId"] shouldBe approved.id.toString()
            payload["amount"] shouldBe mapOf("amountMinor" to TOTAL, "currency" to "BRL")
            payload["status"] shouldBe "approved"
            payload["providerReference"] shouldBe "sim_ch_000123"
            payload.containsKey("reasonCategory") shouldBe false
            payload.containsKey("pendingReason") shouldBe false

            PaymentEnvelopes.typeOf(PaymentEvent.ChargeRecorded(declined)) shouldBe EventType.PaymentDeclined
            PaymentEnvelopes.payloadOf(PaymentEvent.ChargeRecorded(declined))["reasonCategory"] shouldBe "card_expired"
            PaymentEnvelopes.typeOf(PaymentEvent.ChargeRecorded(pending)) shouldBe EventType.PaymentPending
            val pendingPayload = PaymentEnvelopes.payloadOf(PaymentEvent.ChargeRecorded(pending))
            pendingPayload["pendingReason"] shouldBe "PROVIDER_UNAVAILABLE"
            pendingPayload.containsKey("providerReference") shouldBe false
            shouldThrow<IllegalStateException> { PaymentEnvelopes.typeOf(PaymentEvent.ChargeRecorded(pending.void())) }
        }

        test("a refund maps to RefundRecorded keyed by the refunded charge, with the recipient snapshot") {
            val charge = attempt(ProviderDecision.Approved(ProviderReference("sim_ch_1")))
            val refund =
                RefundRecord(
                    RefundId(UUID.randomUUID()),
                    charge.orderId,
                    ADA,
                    charge.id,
                    charge.amount,
                    ProviderReference("sim_rf_000045"),
                    IdempotencyKey(UUID.randomUUID()),
                    AT,
                    AT,
                )
            val event = PaymentEvent.RefundRecorded(refund, Recipient(ADA, "ada@example.test", null, listOf("email")))
            val envelope =
                PaymentEnvelopes.envelopeOf(
                    event,
                    "corr-2",
                    EnvelopeFactory("payment", Clock.fixed(AT, ZoneOffset.UTC)),
                )

            envelope.type shouldBe EventType.RefundRecorded.name
            envelope.aggregateId shouldBe charge.id.value
            val payload = PaymentEnvelopes.payloadOf(event)
            payload["refundId"] shouldBe refund.id.toString()
            payload["paymentId"] shouldBe charge.id.toString()
            payload["providerReference"] shouldBe "sim_rf_000045"
            val recipient = payload["recipient"] as Map<*, *>
            recipient.containsKey("phone") shouldBe true
            recipient["phone"] shouldBe null
            recipient["preferredChannels"] shouldBe listOf("email")
        }
    })
