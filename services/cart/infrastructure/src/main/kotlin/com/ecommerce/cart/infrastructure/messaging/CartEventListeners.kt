package com.ecommerce.cart.infrastructure.messaging

import arrow.core.getOrElse
import com.ecommerce.cart.application.DeleteAccountCart
import com.ecommerce.cart.application.RemoveOrderedLines
import com.ecommerce.cart.domain.AccountId
import com.ecommerce.cart.domain.OrderedItem
import com.ecommerce.cart.domain.ProductId
import com.ecommerce.platform.messaging.consumer.EventListenerSupport
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.envelope.ReceivedEnvelope
import com.ecommerce.platform.messaging.envelope.Topic
import com.ecommerce.platform.messaging.envelope.payloadAs
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import java.time.Instant
import java.util.UUID

/**
 * Inbound adapter: the events the cart consumes (consumer group `cart`, events.yaml `cartConsumesAccount` and the
 * cart's share of `order.order.v1`), each handled once per `eventId` through [EventListenerSupport]:
 * `OrderPaid` takes the ordered quantities out of the account cart (the synchronous clear by order reaches the
 * same end state), `AccountDeleted` discards the account cart. Other event types on the topics are acknowledged and
 * ignored. A failure (for example a lost optimistic-locking race) is thrown so the container retries the record.
 */
class CartEventListeners(
    private val events: EventListenerSupport,
    private val removeOrderedLines: RemoveOrderedLines,
    private val deleteAccountCart: DeleteAccountCart,
) {
    @KafkaListener(topics = [Topic.ORDER])
    fun onOrderEvent(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        events.dispatch(record, ack) { envelope -> if (envelope.type == EventType.OrderPaid.name) orderPaid(envelope) }
    }

    @KafkaListener(topics = [Topic.ACCOUNT])
    fun onAccountEvent(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        events.dispatch(record, ack) { envelope ->
            if (envelope.type == EventType.AccountDeleted.name) {
                deleteAccountCart(AccountId(envelope.payloadAs<AccountDeletedPayload>().accountId))
            }
        }
    }

    private suspend fun orderPaid(envelope: ReceivedEnvelope) {
        val paid = envelope.payloadAs<OrderPaidPayload>()
        val ordered = paid.lines.map { OrderedItem(ProductId(it.productId), it.quantity) }
        removeOrderedLines(AccountId(paid.accountId), ordered, paid.paidAt).getOrElse { failure ->
            error("OrderPaid ${envelope.eventId} could not update the cart: $failure")
        }
    }
}

/** The part of events.yaml `OrderPaidPayload` the cart reads. */
data class OrderPaidPayload(
    val accountId: UUID,
    val lines: List<OrderLinePayload>,
    /** Lines added to the cart after this instant were not part of the order (required by events.yaml). */
    val paidAt: Instant? = null,
)

/** The part of events.yaml `OrderLine` the cart reads. */
data class OrderLinePayload(
    val productId: UUID,
    val quantity: Int,
)

/** The part of events.yaml `AccountDeletedPayload` the cart reads. */
data class AccountDeletedPayload(
    val accountId: UUID,
)
