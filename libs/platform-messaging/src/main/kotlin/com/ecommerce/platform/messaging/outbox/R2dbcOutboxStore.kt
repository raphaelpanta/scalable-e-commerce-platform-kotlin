package com.ecommerce.platform.messaging.outbox

import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.EventEnvelope
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.observability.Traceparents
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.reactive.TransactionSynchronizationManager

/**
 * Kafka header names copied from the envelope onto every relayed record, plus the W3C `traceparent` of the span that
 * wrote the event (T188, FR-025): the relay sends the record later, outside that span, so the outbox keeps it for the
 * consumer's listener observation to continue the producer's trace.
 */
object OutboxHeaders {
    const val EVENT_ID = "eventId"
    const val TYPE = "type"
    const val CORRELATION_ID = "correlationId"
    const val TRACEPARENT = Traceparents.HEADER

    /** The headers of [envelope], in the order they are written; `traceparent` only when a span was current. */
    fun of(
        envelope: EventEnvelope,
        traceparent: String? = null,
    ): Map<String, String> =
        linkedMapOf(
            EVENT_ID to envelope.eventId.toString(),
            TYPE to envelope.type,
            CORRELATION_ID to envelope.correlationId,
        ).apply { traceparent?.let { put(TRACEPARENT, it) } }
}

/**
 * [OutboxPublisher] on the service's own PostgreSQL through [DatabaseClient]. The insert joins the caller's
 * reactive transaction (the transaction context travels in the Reactor context that coroutines carry), so it
 * commits or rolls back with the aggregate change; without an active transaction it refuses to write. The headers
 * record the caller's `traceparent` ([Traceparents.current]: the request's or the listener's span).
 */
class R2dbcOutboxStore(
    private val database: DatabaseClient,
) : OutboxPublisher {
    override suspend fun publish(envelope: EventEnvelope) {
        check(inTransaction()) {
            "OutboxPublisher.publish must run inside the transaction of the aggregate change " +
                "(TransactionalOperator.executeAndAwait or a @Transactional suspend function)"
        }
        val key = envelope.aggregateId.toString()
        database
            .sql(INSERT)
            .bind("id", envelope.eventId)
            .bind("aggregateId", key)
            .bind("topic", EventType.topicOf(envelope.type))
            .bind("eventType", envelope.type)
            .bind("eventKey", key)
            .bind("payload", EnvelopeJson.write(envelope))
            .bind("headers", EnvelopeJson.mapper.writeValueAsString(OutboxHeaders.of(envelope, Traceparents.current())))
            .bind("occurredAt", envelope.occurredAt)
            .await()
    }

    private suspend fun inTransaction(): Boolean =
        TransactionSynchronizationManager
            .forCurrentTransaction()
            .map { it.isActualTransactionActive }
            .onErrorReturn(NoTransactionException::class.java, false)
            .awaitSingle()

    private companion object {
        const val INSERT =
            "INSERT INTO outbox (id, aggregate_id, topic, event_type, event_key, payload, headers, occurred_at) " +
                "VALUES (:id, :aggregateId, :topic, :eventType, :eventKey, CAST(:payload AS jsonb), " +
                "CAST(:headers AS jsonb), :occurredAt)"
    }
}
