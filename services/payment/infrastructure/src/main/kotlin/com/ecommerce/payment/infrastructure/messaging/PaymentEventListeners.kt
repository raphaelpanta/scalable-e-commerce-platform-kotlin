package com.ecommerce.payment.infrastructure.messaging

import com.ecommerce.platform.messaging.consumer.EventListenerSupport
import com.ecommerce.platform.messaging.envelope.Topic
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment

/**
 * Kafka listener of the payment service (consumer group = the context name, `payment`; events.yaml
 * `paymentConsumesOrder`). Each record is handled once per `eventId` by [EventListenerSupport] (documented blocking
 * exception: the listener thread waits for the handler).
 */
class PaymentEventListeners(
    private val events: EventListenerSupport,
    private val handlers: PaymentEventHandlers,
) {
    @KafkaListener(topics = [Topic.ORDER])
    fun onOrder(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        events.dispatch(record, ack) { handlers.onOrderEvent(it) }
    }
}
