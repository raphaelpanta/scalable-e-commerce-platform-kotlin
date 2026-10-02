package com.ecommerce.platform.messaging.envelope

/**
 * Registry of the event types of contracts/asyncapi/events.yaml: the entry name is the envelope `type` and
 * [topic] is the channel the event is published to. The producing context is the first topic segment.
 */
enum class EventType(
    val topic: String,
) {
    AccountRegistered(Topic.ACCOUNT),
    AccountVerified(Topic.ACCOUNT),
    AccountDeleted(Topic.ACCOUNT),
    PasswordResetRequested(Topic.ACCOUNT),
    CartMerged(Topic.CART),
    StockReserved(Topic.STOCK),
    StockReservationReleased(Topic.STOCK),
    StockCommitted(Topic.STOCK),
    OrderPlaced(Topic.ORDER),
    OrderPaid(Topic.ORDER),
    OrderPaymentFailed(Topic.ORDER),
    OrderPreparing(Topic.ORDER),
    OrderShipped(Topic.ORDER),
    OrderDelivered(Topic.ORDER),
    OrderCancelled(Topic.ORDER),
    PaymentPending(Topic.PAYMENT),
    PaymentApproved(Topic.PAYMENT),
    PaymentDeclined(Topic.PAYMENT),
    RefundRecorded(Topic.PAYMENT),
    NotificationSent(Topic.NOTIFICATION),
    NotificationFailed(Topic.NOTIFICATION),
    ;

    /** The producing context (`identity`, `catalog`, `cart`, `order`, `payment` or `notification`). */
    val producer: String get() = topic.substringBefore('.')

    companion object {
        private val BY_NAME: Map<String, EventType> = entries.associateBy { it.name }

        /** The registered type named [type], or null for a type this version does not know (additive evolution). */
        fun find(type: String): EventType? = BY_NAME[type]

        /** The topic of the registered type named [type]; an unknown type cannot be published. */
        fun topicOf(type: String): String = requireNotNull(find(type)) { "unknown event type '$type'" }.topic

        /** The registered types published to [topic], in contract order. */
        fun publishedTo(topic: String): List<EventType> = entries.filter { it.topic == topic }
    }
}
