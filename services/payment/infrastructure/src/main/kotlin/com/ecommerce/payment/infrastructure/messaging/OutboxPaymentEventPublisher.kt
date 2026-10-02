package com.ecommerce.payment.infrastructure.messaging

import com.ecommerce.payment.application.PaymentEventPublisher
import com.ecommerce.payment.domain.PaymentEvent
import com.ecommerce.platform.correlation.CorrelationIds
import com.ecommerce.platform.messaging.envelope.EnvelopeFactory
import com.ecommerce.platform.messaging.outbox.OutboxPublisher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.withContext
import reactor.util.context.Context
import java.util.UUID

/**
 * Publishes the payment events through the transactional outbox of platform-messaging (topic `payment.payment.v1`,
 * key = payment attempt id) in the caller's transaction, stamped with the correlation id of the request or event
 * being handled (a fresh one outside both).
 */
class OutboxPaymentEventPublisher(
    private val outbox: OutboxPublisher,
    private val envelopes: EnvelopeFactory,
) : PaymentEventPublisher {
    override suspend fun publish(event: PaymentEvent) {
        val correlationId = CorrelationIds.current() ?: UUID.randomUUID().toString()
        outbox.publish(PaymentEnvelopes.envelopeOf(event, correlationId, envelopes))
    }
}

/**
 * Runs [block] with [correlationId] as the correlation id of the Reactor context, keeping the context already there
 * (an event consumer's transaction), so that the events it publishes carry the incoming correlation id.
 */
suspend fun <T> withCorrelationId(
    correlationId: String,
    block: suspend () -> T,
): T {
    val context = currentCoroutineContext()[ReactorContext]?.context ?: Context.empty()
    return withContext(ReactorContext(context.put(CorrelationIds.CONTEXT_KEY, correlationId))) { block() }
}
