package com.ecommerce.platform.messaging.envelope

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonTypeRef
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Maximum length of `correlationId` (events.yaml `Envelope.correlationId.maxLength`). */
const val MAX_CORRELATION_ID_LENGTH = 64

/**
 * The envelope shared by every event (contracts/asyncapi/events.yaml `Envelope`). [eventId] is the idempotency
 * key of consumers, [aggregateId] the Kafka message key, [version] equals the topic suffix (1). Producers build
 * an `Envelope<TheirPayload>`; consumers read an `Envelope<JsonNode>` ([ReceivedEnvelope]) and call [payloadAs].
 */
data class Envelope<out P : Any>(
    val eventId: UUID,
    val type: String,
    val version: Int,
    val occurredAt: Instant,
    val aggregateId: UUID,
    val correlationId: String,
    val producer: String,
    val payload: P,
) {
    init {
        require(type.isNotBlank()) { "event type must not be blank" }
        require(version >= 1) { "event version must be at least 1, was $version" }
        require(correlationId.isNotBlank() && correlationId.length <= MAX_CORRELATION_ID_LENGTH) {
            "correlationId must have 1 to $MAX_CORRELATION_ID_LENGTH characters"
        }
        require(producer.isNotBlank()) { "producer must not be blank" }
    }

    companion object {
        /** The schema version of every v1 topic. */
        const val VERSION: Int = 1

        /**
         * A new envelope for [type] with a fresh [eventId], produced by the context that owns the type's topic
         * ([EventType.producer]); [occurredAt] is truncated to microseconds, the precision
         * PostgreSQL keeps, so the published value equals the stored one.
         */
        fun <P : Any> of(
            type: EventType,
            aggregateId: UUID,
            correlationId: String,
            payload: P,
            occurredAt: Instant = Instant.now(),
        ): Envelope<P> =
            Envelope(
                eventId = UUID.randomUUID(),
                type = type.name,
                version = VERSION,
                occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS),
                aggregateId = aggregateId,
                correlationId = correlationId,
                producer = type.producer,
                payload = payload,
            )
    }
}

/** Any envelope, whatever its payload type: what the outbox publishes and the idempotent consumer records. */
typealias EventEnvelope = Envelope<Any>

/** An envelope read from Kafka: the payload stays a JSON tree until the consumer converts it with [payloadAs]. */
typealias ReceivedEnvelope = Envelope<JsonNode>

/** The registered [EventType] of this envelope, or null when the type is unknown to this version. */
fun Envelope<*>.eventType(): EventType? = EventType.find(type)

/** The payload converted to [T] with [EnvelopeJson] (unknown payload fields are ignored). */
inline fun <reified T : Any> Envelope<*>.payloadAs(): T = EnvelopeJson.convert(payload, jacksonTypeRef<T>())

/** Builds envelopes stamped with this service's producer name and clock (`platform.messaging.producer`). */
class EnvelopeFactory(
    val producer: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** A new envelope of [type] for [aggregateId], occurring now. */
    fun <P : Any> create(
        type: EventType,
        aggregateId: UUID,
        correlationId: String,
        payload: P,
    ): Envelope<P> = Envelope.of(type, aggregateId, correlationId, payload, clock.instant()).copy(producer = producer)
}
