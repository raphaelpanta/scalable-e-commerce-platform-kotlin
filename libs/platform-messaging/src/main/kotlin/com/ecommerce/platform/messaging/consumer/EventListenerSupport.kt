package com.ecommerce.platform.messaging.consumer

import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.MalformedEnvelopeException
import com.ecommerce.platform.messaging.envelope.ReceivedEnvelope
import kotlinx.coroutines.reactor.mono
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.support.Acknowledgment

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
        // Blocking wait on the Kafka consumer thread (see the class documentation).
        val handled = mono { idempotent.handle(envelope, consumer, block) }.block()
        ack.acknowledge()
        return checkNotNull(handled) { "no outcome for record ${coordinates(record)}" }
    }

    private fun coordinates(record: ConsumerRecord<*, *>): String =
        "${record.topic()}-${record.partition()}@${record.offset()}"
}
