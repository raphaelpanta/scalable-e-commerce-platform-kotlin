package com.ecommerce.order.infrastructure.web

import com.ecommerce.order.domain.Order
import com.ecommerce.order.domain.Page
import java.time.Instant
import java.util.UUID

/** `Money` of order.yaml. */
data class MoneyView(
    val amountMinor: Long,
    val currency: String,
)

/** `OrderLine` of order.yaml. */
data class OrderLineView(
    val productId: UUID,
    val name: String,
    val unitPrice: MoneyView,
    val quantity: Int,
    val lineTotal: MoneyView,
)

/** `DeliveryAddress` of order.yaml. */
data class DeliveryAddressView(
    val recipientName: String,
    val line1: String,
    val line2: String?,
    val city: String,
    val postalCode: String,
    val country: String,
)

/** `StatusChange` of order.yaml. */
data class StatusChangeView(
    val kind: String,
    val status: String,
    val at: Instant,
    val by: String,
)

/**
 * `Order` of order.yaml, plus the additive `orderNumber` (service conventions section 8). `paymentExpiresAt` is the end
 * of the configured payment window: non-null exactly while the payment is `pending` (feature 005, FR-009 countdown).
 */
data class OrderView(
    val id: UUID,
    val orderNumber: String,
    val orderStatus: String,
    val paymentStatus: String,
    val cancellationReason: String?,
    val lines: List<OrderLineView>,
    val total: MoneyView,
    val deliveryAddress: DeliveryAddressView,
    val statusHistory: List<StatusChangeView>,
    val createdAt: Instant,
    val paymentAttemptId: UUID?,
    val paymentExpiresAt: Instant?,
) {
    companion object {
        fun of(order: Order): OrderView =
            OrderView(
                id = order.id.value,
                orderNumber = order.number.value,
                orderStatus = order.orderStatus.wire,
                paymentStatus = order.paymentStatus.wire,
                cancellationReason = order.cancellation?.reason?.name,
                lines =
                    order.lines.map {
                        OrderLineView(
                            it.productId.value,
                            it.name,
                            MoneyView(it.unitPrice.amountMinor, it.unitPrice.currency),
                            it.quantity.value,
                            MoneyView(it.lineTotal.amountMinor, it.lineTotal.currency),
                        )
                    },
                total = MoneyView(order.total.amountMinor, order.total.currency),
                deliveryAddress =
                    order.deliveryAddress.let {
                        DeliveryAddressView(it.recipientName, it.line1, it.line2, it.city, it.postalCode, it.country)
                    },
                statusHistory = order.history.map { StatusChangeView(it.kind.wire, it.status, it.at, it.by) },
                createdAt = order.placedAt,
                paymentAttemptId = order.paymentAttemptId?.value,
                paymentExpiresAt = order.paymentExpiresAt,
            )
    }
}

/** `OrderPage` of order.yaml. */
data class OrderPageView(
    val items: List<OrderView>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
) {
    companion object {
        fun of(page: Page<Order>): OrderPageView =
            OrderPageView(page.items.map(OrderView::of), page.request.page, page.request.size, page.totalItems)
    }
}
