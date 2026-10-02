package com.ecommerce.platform.messaging.outbox

import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.EventEnvelope
import com.ecommerce.platform.messaging.envelope.EventType
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.reactive.TransactionSynchronizationManager

/** Kafka header names copied from the envelope onto every relayed record. */
object OutboxHeaders {
    const val EVENT_ID = "eventId"
    const val TYPE = "type"
    const val CORRELATION_ID = "correlationId"

    /** The headers of [envelope], in the order they are written. */
    fun of(envelope: EventEnvelope): Map<String, String> =
        linkedMapOf(
            EVENT_ID to envelope.eventId.toString(),
            TYPE to envelope.type,
            CORRELATION_ID to envelope.correlationId,
        )
}

/**
 * [OutboxPublisher] on the service's own PostgreSQL through [DatabaseClient]. The insert joins the caller's
 * reactive transaction (the transaction context travels in the Reactor context that coroutines carry), so it
 * commits or rolls back with the aggregate change; without an active transaction it refuses to write.
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
            .bind("headers", EnvelopeJson.mapper.writeValueAsString(OutboxHeaders.of(envelope)))
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
