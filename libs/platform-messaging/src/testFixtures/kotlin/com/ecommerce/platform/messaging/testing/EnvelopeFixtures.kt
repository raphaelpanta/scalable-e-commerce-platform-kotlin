package com.ecommerce.platform.messaging.testing

import com.ecommerce.platform.messaging.envelope.Envelope
import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.envelope.ReceivedEnvelope
import java.time.Instant
import java.util.UUID

/**
 * Example envelopes for tests, with the identifiers of contracts/internal/pact-interactions.md §3.1. Payloads
 * are maps so that the fixtures stay independent of the services' payload classes.
 */
object EnvelopeFixtures {
    val ACCOUNT_ID: UUID = UUID.fromString("7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d")
    val ORDER_ID: UUID = UUID.fromString("0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10")
    val PAYMENT_ID: UUID = UUID.fromString("c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50")
    const val CORRELATION_ID = "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"
    const val ORDER_TOTAL_MINOR = 1999L
    val OCCURRED_AT: Instant = Instant.parse("2026-10-02T10:00:00Z")

    /**
     * An envelope of [type] with a fresh `eventId`, occurring at [OCCURRED_AT] (use `copy` for another instant);
     * the producer is the context that owns [type]'s topic.
     */
    fun <P : Any> envelope(
        type: EventType,
        payload: P,
        aggregateId: UUID = ORDER_ID,
        eventId: UUID = UUID.randomUUID(),
        correlationId: String = CORRELATION_ID,
    ): Envelope<P> =
        Envelope(
            eventId = eventId,
            type = type.name,
            version = Envelope.VERSION,
            occurredAt = OCCURRED_AT,
            aggregateId = aggregateId,
            correlationId = correlationId,
            producer = type.producer,
            payload = payload,
        )

    /** An `OrderPlaced` for [orderId] (orderStatus placed, paymentStatus pending). */
    fun orderPlaced(
        orderId: UUID = ORDER_ID,
        eventId: UUID = UUID.randomUUID(),
    ): Envelope<Map<String, Any>> =
        envelope(
            EventType.OrderPlaced,
            mapOf(
                "orderId" to orderId.toString(),
                "accountId" to ACCOUNT_ID.toString(),
                "orderStatus" to "placed",
                "paymentStatus" to "pending",
                "total" to mapOf("amountMinor" to ORDER_TOTAL_MINOR, "currency" to "BRL"),
            ),
            aggregateId = orderId,
            eventId = eventId,
        )

    /** A `PaymentApproved` for [paymentId] of [orderId]. */
    fun paymentApproved(
        paymentId: UUID = PAYMENT_ID,
        orderId: UUID = ORDER_ID,
        eventId: UUID = UUID.randomUUID(),
    ): Envelope<Map<String, Any>> =
        envelope(
            EventType.PaymentApproved,
            mapOf("paymentId" to paymentId.toString(), "orderId" to orderId.toString(), "outcome" to "approved"),
            aggregateId = paymentId,
            eventId = eventId,
        )

    /** [envelope] as a consumer receives it: serialised with [EnvelopeJson] and read back. */
    fun received(envelope: Envelope<*>): ReceivedEnvelope = EnvelopeJson.read(EnvelopeJson.write(envelope))
}
