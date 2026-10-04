package com.ecommerce.platform.messaging.consumer

import com.ecommerce.platform.core.values.CorrelationId
import com.ecommerce.platform.correlation.CorrelationIds
import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.MalformedEnvelopeException
import com.ecommerce.platform.messaging.envelope.ReceivedEnvelope
import com.ecommerce.platform.observability.ReactorThreadLocals
import kotlinx.coroutines.reactor.mono
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.MDC
import org.springframework.kafka.support.Acknowledgment
import reactor.util.context.Context

/**
 * Bridges a plain `@KafkaListener` method to [IdempotentConsumer]:
 *
 * ```kotlin
 * @KafkaListener(topics = [Topic.ORDER])
 * fun onOrder(record: ConsumerRecord<String, String>, ack: Acknowledgment) =
 *     events.dispatch(record, ack) { envelope -> when (envelope.type) { ... } }
 * ```
 *
 * The record value is parsed with [EnvelopeJson] (a malformed value goes straight to the dead-letter topic), the
 * suspending block runs once per `eventId` through [IdempotentConsumer], and the offset is acknowledged
 * (`MANUAL_IMMEDIATE`) after the transaction committed, also for a duplicate. An exception propagates to the
 * container's error handler, which retries with backoff and then publishes the record to `<topic>.dlt`.
 *
 * The envelope's `correlationId` (kept when it is 1 to 64 characters of `[A-Za-z0-9-]`, otherwise replaced by a UUID,
 * as [CorrelationId.sanitise] does for requests) is bound around the handler (T145, FR-025): in the MDC of the
 * consumer thread, and in the Reactor context of the handler's coroutine ([CorrelationIds.bind]), which
 * [ReactorThreadLocals] restores into the MDC wherever the coroutine resumes. Every log line of the handler therefore
 * carries `correlationId`, [CorrelationIds.current] returns it, and the internal calls and events of the handler copy
 * it.
 *
 * The trace continues too (T188, FR-025): the listener container's observation (`observationEnabled`) starts its
 * span from the record's `traceparent` header, which the outbox stored when the producer wrote the event, and
 * [ReactorThreadLocals.capture] carries that observation into the handler's Reactor context (explicitly, not only
 * through Reactor's automatic context propagation of `block()`). The handler's log lines therefore carry the
 * producer's `traceId`, and its internal calls and the events it writes continue the trace.
 *
 * The listener method stays non-suspending on purpose: Spring Kafka hands a `suspend` listener's next record over
 * before the previous one completes, which would break the per-key ordering of events.yaml. The Kafka consumer
 * thread (owned by the listener container, never a Netty or Reactor event loop) therefore waits for the
 * coroutine; this is the documented blocking exception of the consumer side (docs/build.md).
 */
class EventListenerSupport(
    private val idempotent: IdempotentConsumer,
    /** The name recorded in `processed_event.consumer`; defaults to the consumer group (the context name). */
    val consumer: String,
) {
    /** Handles [record] once per `eventId` and acknowledges it; returns what [IdempotentConsumer.handle] returned. */
    fun <T> dispatch(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
        block: suspend (ReceivedEnvelope) -> T,
    ): Handled<T> {
        val value: String =
            record.value() ?: throw MalformedEnvelopeException("record ${coordinates(record)} has no value")
        val envelope = EnvelopeJson.read(value)
        val correlationId = CorrelationId.sanitise(envelope.correlationId).value.value
        val handled =
            MDC.putCloseable(CorrelationIds.MDC_KEY, correlationId).use {
                // Blocking wait on the Kafka consumer thread (see the class documentation).
                mono(ReactorThreadLocals.INSTANCE) { idempotent.handle(envelope, consumer, block) }
                    .contextWrite { context -> handlerContext(context, correlationId) }
                    .block()
            }
        ack.acknowledge()
        return checkNotNull(handled) { "no outcome for record ${coordinates(record)}" }
    }

    /** The listener's thread locals (observation, hence the trace) and the envelope's correlation id. */
    private fun handlerContext(
        context: Context,
        correlationId: String,
    ): Context = CorrelationIds.bind(ReactorThreadLocals.capture(context), correlationId)

    private fun coordinates(record: ConsumerRecord<*, *>): String =
        "${record.topic()}-${record.partition()}@${record.offset()}"
}
