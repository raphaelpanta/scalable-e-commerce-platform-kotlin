package com.ecommerce.order.infrastructure.messaging

import com.ecommerce.order.application.OrderEventPublisher
import com.ecommerce.order.domain.OrderEvent
import com.ecommerce.platform.correlation.CorrelationIds
import com.ecommerce.platform.messaging.envelope.Envelope
import com.ecommerce.platform.messaging.envelope.EnvelopeFactory
import com.ecommerce.platform.messaging.outbox.OutboxPublisher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.withContext
import reactor.util.context.Context
import java.util.UUID

/**
 * Publishes the order events through the transactional outbox of platform-messaging (topic `order.order.v1`,
 * key = order id), stamped with the correlation id of the request or event being handled.
 */
class OutboxOrderEventPublisher(
    private val outbox: OutboxPublisher,
    private val envelopes: EnvelopeFactory,
) : OrderEventPublisher {
    override suspend fun publish(events: List<OrderEvent>) {
        if (events.isEmpty()) return
        val correlationId = CorrelationIds.current() ?: UUID.randomUUID().toString()
        outbox.publishAll(events.map { envelopeOf(it, correlationId) })
    }

    /** The envelope of [event] as it is published. */
    fun envelopeOf(
        event: OrderEvent,
        correlationId: String,
    ): Envelope<Any> =
        envelopes.create(
            OrderEventPayloads.typeOf(event),
            event.order.id.value,
            correlationId,
            OrderEventPayloads.payloadOf(event),
        )
}

/**
 * Runs [block] with [correlationId] as the correlation id of the Reactor context, keeping the context already
 * there (an event consumer's transaction), so that derived events and calls carry it.
 */
suspend fun <T> withCorrelationId(
    correlationId: String,
    block: suspend () -> T,
): T {
    val context = currentCoroutineContext()[ReactorContext]?.context ?: Context.empty()
    return withContext(ReactorContext(context.put(CorrelationIds.CONTEXT_KEY, correlationId))) { block() }
}
