package com.ecommerce.order.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Duration
import java.time.Instant

/**
 * The order aggregate (data-model section 3.4): frozen [lines], [deliveryAddress] and [recipient] snapshots, and two
 * independent statuses (docs/adr/0001-two-status-order-model.md). Every
 * change is a pure function returning an [OrderChange]; [version] is the persisted version the change was made
 * from (optimistic locking happens in the repository). [history] is append-only.
 */
data class Order(
    val id: OrderId,
    val number: OrderNumber,
    val accountId: AccountId,
    val accountPseudonym: String?,
    val lines: List<OrderLine>,
    val deliveryAddress: DeliveryAddress,
    val recipient: Recipient,
    val paymentMethodRef: String,
    val idempotencyKey: IdempotencyKey,
    val reservationId: ReservationId,
    val orderStatus: OrderStatus,
    val paymentStatus: PaymentStatus,
    val cancellation: Cancellation?,
    val declineCategory: DeclineCategory?,
    val paymentAttemptId: PaymentAttemptId?,
    val paidAt: Instant?,
    val paymentExpiresAt: Instant?,
    val refund: Refund?,
    val placedAt: Instant,
    val history: List<StatusChange>,
    val version: Long,
) {
    init {
        require(lines.isNotEmpty()) { "an order has at least one line" }
        require((cancellation != null) == (orderStatus == OrderStatus.CANCELLED)) {
            "a cancellation is recorded if and only if the order is cancelled"
        }
        require(orderStatus !in FULFILMENT || paymentStatus == PaymentStatus.APPROVED) {
            "an order in fulfilment has an approved payment"
        }
        require(paymentStatus != PaymentStatus.PENDING || orderStatus == OrderStatus.PLACED) {
            "a pending payment belongs to a placed order"
        }
    }

    /** Sum of the line totals (no tax, no shipping in the MVP). */
    val total: Money get() = lines.map(OrderLine::lineTotal).reduce(Money::plus)

    /** True when [caller] may read this order: its owner, or any operator. */
    fun isVisibleTo(caller: Caller): Boolean = caller.isOperator || (caller.isShopper && caller.accountId == accountId)

    /** This order unchanged, with no event. */
    fun unchanged(): OrderChange = OrderChange(this, emptyList())

    companion object {
        /** How long a payment may stay `pending` before the order is cancelled with `PAYMENT_EXPIRED`. */
        val PAYMENT_WINDOW: Duration = Duration.ofMinutes(30)

        private val FULFILMENT = setOf(OrderStatus.PREPARING, OrderStatus.SHIPPED, OrderStatus.DELIVERED)

        /**
         * Places an order: `placed` with a `pending` payment whose 30-minute clock starts now, two history entries
         * and an `OrderPlaced` event. Refused when the total is not positive (a charge needs a positive amount).
         */
        fun place(placement: Placement): Either<OrderError, OrderChange> {
            if (placement.lines.isEmpty()) return OrderError.EmptyCart.left()
            val at = placement.placedAt
            val order =
                Order(
                    id = placement.id,
                    number = placement.number,
                    accountId = placement.accountId,
                    accountPseudonym = null,
                    lines = placement.lines,
                    deliveryAddress = placement.deliveryAddress,
                    recipient = placement.recipient,
                    paymentMethodRef = placement.paymentMethodRef,
                    idempotencyKey = placement.idempotencyKey,
                    reservationId = placement.reservationId,
                    orderStatus = OrderStatus.PLACED,
                    paymentStatus = PaymentStatus.PENDING,
                    cancellation = null,
                    declineCategory = null,
                    paymentAttemptId = null,
                    paidAt = null,
                    paymentExpiresAt = at.plus(PAYMENT_WINDOW),
                    refund = null,
                    placedAt = at,
                    history =
                        listOf(
                            StatusChange.order(null, OrderStatus.PLACED, at, Actor.Shopper(placement.accountId)),
                            StatusChange.payment(null, PaymentStatus.PENDING, at),
                        ),
                    version = 0,
                )
            return if (order.total.amountMinor > 0) {
                OrderChange(order, listOf(OrderEvent.Placed(order, at))).right()
            } else {
                OrderError.Invalid("cart", "the order total must be positive").left()
            }
        }
    }
}

/** Everything a new order is made of, gathered by the checkout. */
data class Placement(
    val id: OrderId,
    val number: OrderNumber,
    val accountId: AccountId,
    val lines: List<OrderLine>,
    val deliveryAddress: DeliveryAddress,
    val recipient: Recipient,
    val paymentMethodRef: String,
    val idempotencyKey: IdempotencyKey,
    val reservationId: ReservationId,
    val placedAt: Instant,
)

/** The result of a change: the new state and the events to publish with it (none when nothing changed). */
data class OrderChange(
    val order: Order,
    val events: List<OrderEvent>,
)

/** Domain events of the order context; each carries the order as it is after the change. */
sealed interface OrderEvent {
    val order: Order
    val at: Instant

    /** The order was placed (`placed` / `pending`). */
    data class Placed(
        override val order: Order,
        override val at: Instant,
    ) : OrderEvent

    /** The payment was approved; the order stays `placed`. */
    data class Paid(
        override val order: Order,
        override val at: Instant,
    ) : OrderEvent

    /** The payment was declined and the order cancelled with `PAYMENT_FAILED`. */
    data class PaymentFailed(
        override val order: Order,
        override val at: Instant,
    ) : OrderEvent

    /** An operator moved the order to `preparing`, `shipped` or `delivered`. */
    data class StatusChanged(
        override val order: Order,
        override val at: Instant,
        val changedBy: AccountId,
    ) : OrderEvent

    /** The order was cancelled (`SHOPPER_REQUEST`, `OPERATOR` or `PAYMENT_EXPIRED`). */
    data class Cancelled(
        override val order: Order,
        override val at: Instant,
    ) : OrderEvent {
        /** True when the payment was approved, so the payment context records a refund. */
        val refundRequired: Boolean get() = order.paymentStatus == PaymentStatus.APPROVED
    }
}
