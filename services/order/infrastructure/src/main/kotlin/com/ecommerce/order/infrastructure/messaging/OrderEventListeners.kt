package com.ecommerce.order.infrastructure.messaging

import com.ecommerce.platform.messaging.consumer.EventListenerSupport
import com.ecommerce.platform.messaging.envelope.Topic
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment

/**
 * Kafka listeners of the order service (consumer group = the context name, `order`). Each record is handled once per
 * `eventId` by [EventListenerSupport] (documented blocking exception: the listener thread waits for the handler).
 */
class OrderEventListeners(
    private val events: EventListenerSupport,
    private val handlers: OrderEventHandlers,
) {
    @KafkaListener(topics = [Topic.PAYMENT])
    fun onPayment(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        events.dispatch(record, ack) { handlers.onPaymentEvent(it) }
    }

    @KafkaListener(topics = [Topic.ACCOUNT])
    fun onAccount(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        events.dispatch(record, ack) { handlers.onAccountEvent(it) }
    }
}
