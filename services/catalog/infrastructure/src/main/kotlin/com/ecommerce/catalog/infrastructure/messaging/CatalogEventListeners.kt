package com.ecommerce.catalog.infrastructure.messaging

import arrow.core.getOrElse
import com.ecommerce.catalog.application.OrderOutcome
import com.ecommerce.catalog.application.SettleOrderReservation
import com.ecommerce.catalog.domain.OrderId
import com.ecommerce.platform.messaging.consumer.EventListenerSupport
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.envelope.ReceivedEnvelope
import com.ecommerce.platform.messaging.envelope.Topic
import com.ecommerce.platform.messaging.envelope.payloadAs
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import java.util.UUID

/**
 * Inbound adapter: the order events catalog consumes (consumer group `catalog`, ADR 0002 safety net), each handled
 * once per `eventId` through [EventListenerSupport]: `OrderPaid` commits the order's reservation,
 * `OrderPaymentFailed` releases it and `OrderCancelled` releases reserved or restocks committed stock (reason
 * `PAYMENT_EXPIRED` reported as `EXPIRED`). Other order events are acknowledged and ignored. A reservation the
 * synchronous path already ended is tolerated; a persisting conflict is thrown so the container retries the record.
 */
class CatalogEventListeners(
    private val events: EventListenerSupport,
    private val settle: SettleOrderReservation,
) {
    @KafkaListener(topics = [Topic.ORDER])
    fun onOrderEvent(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        events.dispatch(record, ack) { envelope -> outcomeOf(envelope)?.let { settle(envelope, it) } }
    }

    private suspend fun settle(
        envelope: ReceivedEnvelope,
        outcome: OrderOutcome,
    ) {
        val orderId = OrderId(envelope.payloadAs<OrderEventPayload>().orderId)
        settle(orderId, outcome, envelope.correlationId).getOrElse { failure ->
            error("${envelope.type} ${envelope.eventId} could not settle the reservation of its order: $failure")
        }
    }

    private companion object {
        const val PAYMENT_EXPIRED = "PAYMENT_EXPIRED"

        fun outcomeOf(envelope: ReceivedEnvelope): OrderOutcome? =
            when (envelope.type) {
                EventType.OrderPaid.name -> {
                    OrderOutcome.PAID
                }

                EventType.OrderPaymentFailed.name -> {
                    OrderOutcome.PAYMENT_FAILED
                }

                EventType.OrderCancelled.name -> {
                    val reason = envelope.payloadAs<OrderEventPayload>().reason
                    if (reason == PAYMENT_EXPIRED) OrderOutcome.PAYMENT_EXPIRED else OrderOutcome.CANCELLED
                }

                else -> {
                    null
                }
            }
    }
}

/** The part of the order event payloads (events.yaml) catalog reads: the order and, on a cancellation, its reason. */
data class OrderEventPayload(
    val orderId: UUID,
    val reason: String? = null,
)
