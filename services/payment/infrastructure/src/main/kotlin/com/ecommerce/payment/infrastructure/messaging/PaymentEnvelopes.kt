package com.ecommerce.payment.infrastructure.messaging

import com.ecommerce.payment.domain.Money
import com.ecommerce.payment.domain.PaymentAttempt
import com.ecommerce.payment.domain.PaymentEvent
import com.ecommerce.payment.domain.PaymentOutcome
import com.ecommerce.payment.domain.Recipient
import com.ecommerce.payment.domain.RefundRecord
import com.ecommerce.platform.messaging.envelope.Envelope
import com.ecommerce.platform.messaging.envelope.EnvelopeFactory
import com.ecommerce.platform.messaging.envelope.EventType

/** `pendingReason` of a `PaymentPending` raised by the simulator's unreachable rule. */
const val PROVIDER_UNAVAILABLE: String = "PROVIDER_UNAVAILABLE"

/**
 * The payment events of contracts/asyncapi/events.yaml on `payment.payment.v1`, keyed by the attempt id
 * (`paymentId`): `PaymentApproved`, `PaymentDeclined`, `PaymentPending` (`PaymentOutcomePayload`) and
 * `RefundRecorded` (`RefundRecordedPayload`). Payloads are JSON objects whose absent optional members are left out,
 * not written as null (pact-interactions.md section 1); `recipient.phone` is the one member that is null when unknown.
 */
object PaymentEnvelopes {
    /** The event type of [event]. */
    fun typeOf(event: PaymentEvent): EventType =
        when (event) {
            is PaymentEvent.ChargeRecorded -> {
                when (event.attempt.outcome) {
                    PaymentOutcome.APPROVED -> EventType.PaymentApproved
                    PaymentOutcome.DECLINED -> EventType.PaymentDeclined
                    PaymentOutcome.PENDING -> EventType.PaymentPending
                }
            }

            is PaymentEvent.RefundRecorded -> {
                EventType.RefundRecorded
            }
        }

    /** The envelope payload of [event]. */
    fun payloadOf(event: PaymentEvent): Map<String, Any?> =
        when (event) {
            is PaymentEvent.ChargeRecorded -> outcomePayload(event.attempt)
            is PaymentEvent.RefundRecorded -> refundPayload(event.refund, event.recipient)
        }

    /** The envelope of [event] as it is published, keyed by the payment attempt. */
    fun envelopeOf(
        event: PaymentEvent,
        correlationId: String,
        envelopes: EnvelopeFactory,
    ): Envelope<Any> = envelopes.create(typeOf(event), aggregateOf(event), correlationId, payloadOf(event))

    private fun aggregateOf(event: PaymentEvent) =
        when (event) {
            is PaymentEvent.ChargeRecorded -> event.attempt.id.value
            is PaymentEvent.RefundRecorded -> event.refund.attemptId.value
        }

    private fun outcomePayload(attempt: PaymentAttempt): Map<String, Any?> =
        buildMap {
            put("paymentId", attempt.id.toString())
            put("orderId", attempt.orderId.toString())
            put("accountId", attempt.accountId.toString())
            put("amount", money(attempt.amount))
            put("status", attempt.outcome.wire)
            put("idempotencyKey", attempt.idempotencyKey.toString())
            attempt.providerReference?.let { put("providerReference", it.value) }
            attempt.declineCategory?.let { put("reasonCategory", it.wire) }
            if (attempt.outcome == PaymentOutcome.PENDING) put("pendingReason", PROVIDER_UNAVAILABLE)
        }

    private fun refundPayload(
        refund: RefundRecord,
        recipient: Recipient,
    ): Map<String, Any?> =
        mapOf(
            "refundId" to refund.id.toString(),
            "paymentId" to refund.attemptId.toString(),
            "orderId" to refund.orderId.toString(),
            "accountId" to refund.accountId.toString(),
            "amount" to money(refund.amount),
            "providerReference" to refund.providerReference.value,
            "recipient" to
                mapOf(
                    "accountId" to recipient.accountId.toString(),
                    "email" to recipient.email,
                    "phone" to recipient.phone,
                    "preferredChannels" to recipient.preferredChannels,
                ),
        )

    private fun money(amount: Money): Map<String, Any> =
        mapOf("amountMinor" to amount.amountMinor, "currency" to amount.currency)
}
