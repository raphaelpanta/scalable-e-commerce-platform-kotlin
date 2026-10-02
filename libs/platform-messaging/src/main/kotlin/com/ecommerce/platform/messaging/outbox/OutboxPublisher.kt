package com.ecommerce.platform.messaging.outbox

import com.ecommerce.platform.messaging.envelope.EventEnvelope

/**
 * Publishes events through the transactional outbox: the envelope is stored in the same database transaction as
 * the aggregate change and reaches Kafka later through the relay, so an event is published if and only if the
 * transaction commits. Callers must run inside a reactive transaction of the service's R2DBC connection factory,
 * for example `transactionalOperator.executeAndAwait { save(aggregate); outbox.publish(event) }` or a
 * `@Transactional suspend fun`; publishing outside a transaction fails with [IllegalStateException].
 */
interface OutboxPublisher {
    /** Stores [envelope] for publication on the topic registered for its type (`EventType.topicOf`). */
    suspend fun publish(envelope: EventEnvelope)

    /** Stores [envelopes] in iteration order; events of one aggregate are relayed in that order. */
    suspend fun publishAll(envelopes: Iterable<EventEnvelope>) {
        envelopes.forEach { publish(it) }
    }
}
