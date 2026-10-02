package com.ecommerce.order.infrastructure.messaging

import com.ecommerce.order.domain.Order
import com.ecommerce.order.domain.OrderEvent
import com.ecommerce.order.domain.OrderStatus
import com.ecommerce.platform.messaging.envelope.EventType
import java.time.Instant
import java.util.UUID

/** `Money` of events.yaml. */
data class MoneyPayload(
    val amountMinor: Long,
    val currency: String,
)

/** `OrderLine` of events.yaml. */
data class OrderLinePayload(
    val productId: UUID,
    val name: String,
    val quantity: Int,
    val unitPrice: MoneyPayload,
)

/** `StockLine` of events.yaml. */
data class StockLinePayload(
    val productId: UUID,
    val quantity: Int,
)

/** `DeliveryAddress` of events.yaml (personal data). */
data class DeliveryAddressPayload(
    val recipientName: String,
    val line1: String,
    val line2: String?,
    val city: String,
    val postalCode: String,
    val country: String,
)

/** `RecipientSnapshot` of events.yaml (personal data, never logged). */
data class RecipientPayload(
    val accountId: UUID,
    val email: String,
    val phone: String?,
    val preferredChannels: List<String>,
)

/** `OrderPlacedPayload`. */
data class OrderPlacedPayload(
    val orderId: UUID,
    val orderNumber: String,
    val accountId: UUID,
    val lines: List<OrderLinePayload>,
    val total: MoneyPayload,
    val orderStatus: String,
    val paymentStatus: String,
    val deliveryAddress: DeliveryAddressPayload,
    val paymentMethodRef: String,
    val idempotencyKey: String,
    val recipient: RecipientPayload,
)

/** `OrderPaidPayload`. */
data class OrderPaidPayload(
    val orderId: UUID,
    val orderNumber: String,
    val accountId: UUID,
    val lines: List<OrderLinePayload>,
    val total: MoneyPayload,
    val orderStatus: String,
    val paymentStatus: String,
    val deliveryAddress: DeliveryAddressPayload,
    val paymentId: UUID?,
    val paidAt: Instant?,
    val recipient: RecipientPayload,
)

/** `OrderPaymentFailedPayload`. */
data class OrderPaymentFailedPayload(
    val orderId: UUID,
    val orderNumber: String,
    val accountId: UUID,
    val total: MoneyPayload,
    val orderStatus: String,
    val paymentStatus: String,
    val cancellationReason: String,
    val reasonCategory: String?,
    val recipient: RecipientPayload,
)

/** `OrderStatusChangedPayload` (`OrderPreparing`, `OrderShipped`, `OrderDelivered`). */
data class OrderStatusChangedPayload(
    val orderId: UUID,
    val orderNumber: String,
    val accountId: UUID,
    val orderStatus: String,
    val paymentStatus: String,
    val changedAt: Instant,
    val changedBy: UUID,
    val recipient: RecipientPayload,
)

/** `OrderCancelledPayload`. */
data class OrderCancelledPayload(
    val orderId: UUID,
    val orderNumber: String,
    val accountId: UUID,
    val lines: List<StockLinePayload>,
    val total: MoneyPayload,
    val orderStatus: String,
    val paymentStatus: String,
    val reason: String,
    val refundRequired: Boolean,
    val paymentId: UUID?,
    val cancelledAt: Instant,
    val recipient: RecipientPayload,
)

/** The envelope type and payload of a domain event (contracts/asyncapi/events.yaml). */
object OrderEventPayloads {
    /** The registered type of [event]. */
    fun typeOf(event: OrderEvent): EventType =
        when (event) {
            is OrderEvent.Placed -> EventType.OrderPlaced
            is OrderEvent.Paid -> EventType.OrderPaid
            is OrderEvent.PaymentFailed -> EventType.OrderPaymentFailed
            is OrderEvent.Cancelled -> EventType.OrderCancelled
            is OrderEvent.StatusChanged -> statusChangedType(event.order.orderStatus)
        }

    /** The payload of [event]. */
    fun payloadOf(event: OrderEvent): Any {
        val order = event.order
        return when (event) {
            is OrderEvent.Placed -> placed(order)
            is OrderEvent.Paid -> paid(order)
            is OrderEvent.PaymentFailed -> paymentFailed(order)
            is OrderEvent.Cancelled -> cancelled(event)
            is OrderEvent.StatusChanged -> statusChanged(event)
        }
    }

    private fun statusChangedType(status: OrderStatus): EventType =
        when (status) {
            OrderStatus.PREPARING -> EventType.OrderPreparing
            OrderStatus.SHIPPED -> EventType.OrderShipped
            OrderStatus.DELIVERED -> EventType.OrderDelivered
            else -> error("no status-changed event for $status")
        }

    private fun placed(order: Order) =
        OrderPlacedPayload(
            orderId = order.id.value,
            orderNumber = order.number.value,
            accountId = order.accountId.value,
            lines = lines(order),
            total = money(order),
            orderStatus = order.orderStatus.wire,
            paymentStatus = order.paymentStatus.wire,
            deliveryAddress = address(order),
            paymentMethodRef = order.paymentMethodRef,
            idempotencyKey = order.idempotencyKey.toString(),
            recipient = recipient(order),
        )

    private fun paid(order: Order) =
        OrderPaidPayload(
            orderId = order.id.value,
            orderNumber = order.number.value,
            accountId = order.accountId.value,
            lines = lines(order),
            total = money(order),
            orderStatus = order.orderStatus.wire,
            paymentStatus = order.paymentStatus.wire,
            deliveryAddress = address(order),
            paymentId = order.paymentAttemptId?.value,
            paidAt = order.paidAt,
            recipient = recipient(order),
        )

    private fun paymentFailed(order: Order) =
        OrderPaymentFailedPayload(
            orderId = order.id.value,
            orderNumber = order.number.value,
            accountId = order.accountId.value,
            total = money(order),
            orderStatus = order.orderStatus.wire,
            paymentStatus = order.paymentStatus.wire,
            cancellationReason = checkNotNull(order.cancellation).reason.name,
            reasonCategory = order.declineCategory?.wire,
            recipient = recipient(order),
        )

    private fun statusChanged(event: OrderEvent.StatusChanged) =
        OrderStatusChangedPayload(
            orderId = event.order.id.value,
            orderNumber = event.order.number.value,
            accountId = event.order.accountId.value,
            orderStatus = event.order.orderStatus.wire,
            paymentStatus = event.order.paymentStatus.wire,
            changedAt = event.at,
            changedBy = event.changedBy.value,
            recipient = recipient(event.order),
        )

    private fun cancelled(event: OrderEvent.Cancelled): OrderCancelledPayload {
        val order = event.order
        return OrderCancelledPayload(
            orderId = order.id.value,
            orderNumber = order.number.value,
            accountId = order.accountId.value,
            lines = order.lines.map { StockLinePayload(it.productId.value, it.quantity.value) },
            total = money(order),
            orderStatus = order.orderStatus.wire,
            paymentStatus = order.paymentStatus.wire,
            reason = checkNotNull(order.cancellation).reason.name,
            refundRequired = event.refundRequired,
            paymentId = order.paymentAttemptId?.value,
            cancelledAt = event.at,
            recipient = recipient(order),
        )
    }
}

private fun lines(order: Order): List<OrderLinePayload> =
    order.lines.map {
        OrderLinePayload(
            it.productId.value,
            it.name,
            it.quantity.value,
            MoneyPayload(it.unitPrice.amountMinor, it.unitPrice.currency),
        )
    }

private fun money(order: Order) = MoneyPayload(order.total.amountMinor, order.total.currency)

private fun address(order: Order) =
    order.deliveryAddress.let {
        DeliveryAddressPayload(it.recipientName, it.line1, it.line2, it.city, it.postalCode, it.country)
    }

private fun recipient(order: Order) =
    RecipientPayload(
        accountId = order.accountId.value,
        email = order.recipient.email,
        phone = order.recipient.phone,
        preferredChannels = order.recipient.channels.map { it.wire },
    )
