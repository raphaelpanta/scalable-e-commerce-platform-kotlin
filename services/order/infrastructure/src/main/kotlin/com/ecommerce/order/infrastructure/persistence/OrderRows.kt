package com.ecommerce.order.infrastructure.persistence

import com.ecommerce.order.domain.AccountId
import com.ecommerce.order.domain.Cancellation
import com.ecommerce.order.domain.CancellationReason
import com.ecommerce.order.domain.Channel
import com.ecommerce.order.domain.DeclineCategory
import com.ecommerce.order.domain.DeliveryAddress
import com.ecommerce.order.domain.IdempotencyKey
import com.ecommerce.order.domain.Money
import com.ecommerce.order.domain.Order
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.OrderLine
import com.ecommerce.order.domain.OrderNumber
import com.ecommerce.order.domain.OrderStatus
import com.ecommerce.order.domain.PaymentAttemptId
import com.ecommerce.order.domain.PaymentStatus
import com.ecommerce.order.domain.ProductId
import com.ecommerce.order.domain.Quantity
import com.ecommerce.order.domain.Recipient
import com.ecommerce.order.domain.Refund
import com.ecommerce.order.domain.RefundId
import com.ecommerce.order.domain.ReservationId
import com.ecommerce.order.domain.StatusChange
import com.ecommerce.order.domain.StatusKind
import io.r2dbc.spi.Readable
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec
import java.time.Instant
import java.util.UUID

/** Binds [value], or a typed NULL when it is absent. */
internal fun GenericExecuteSpec.bindNullable(
    name: String,
    value: Any?,
    type: Class<*>,
): GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)

internal inline fun <reified T : Any> Readable.required(name: String): T =
    checkNotNull(get(name, T::class.javaObjectType)) {
        name
    }

internal inline fun <reified T : Any> Readable.optional(name: String): T? = get(name, T::class.javaObjectType)

/** The order row without its lines and history. */
internal data class OrderHead(
    val id: UUID,
    val build: (List<OrderLine>, List<StatusChange>) -> Order,
) {
    fun toOrder(
        lines: List<OrderLine>,
        history: List<StatusChange>,
    ): Order = build(lines, history)
}

/** Mapping between the order aggregate and its rows. */
internal object OrderRows {
    private const val CHANNEL_SEPARATOR = ","

    /** Every column of the order row. */
    fun bindOrder(
        spec: GenericExecuteSpec,
        order: Order,
    ): GenericExecuteSpec =
        bindMutable(spec, order)
            .bind("orderNumber", order.number.value)
            .bind("accountId", order.accountId.value)
            .bind("totalMinor", order.total.amountMinor)
            .bind("currency", order.total.currency)
            .bind("paymentMethodRef", order.paymentMethodRef)
            .bind("idempotencyKey", order.idempotencyKey.value)
            .bind("reservationId", order.reservationId.value)
            .bind("placedAt", order.placedAt)

    /** The columns a change may touch, plus the id and the version it was made from. */
    fun bindMutable(
        spec: GenericExecuteSpec,
        order: Order,
    ): GenericExecuteSpec {
        val address = order.deliveryAddress
        return spec
            .bind("id", order.id.value)
            .bind("version", order.version)
            .bindNullable("accountPseudonym", order.accountPseudonym, String::class.java)
            .bind("orderStatus", order.orderStatus.wire)
            .bind("paymentStatus", order.paymentStatus.wire)
            .bindNullable("cancellationReason", order.cancellation?.reason?.name, String::class.java)
            .bindNullable("cancelledAt", order.cancellation?.at, Instant::class.java)
            .bindNullable("cancelledBy", order.cancellation?.by, String::class.java)
            .bindNullable("declineCategory", order.declineCategory?.wire, String::class.java)
            .bind("recipientName", address.recipientName)
            .bind("line1", address.line1)
            .bindNullable("line2", address.line2, String::class.java)
            .bind("city", address.city)
            .bindNullable("region", address.region, String::class.java)
            .bind("postalCode", address.postalCode)
            .bind("country", address.country)
            .bind("recipientEmail", order.recipient.email)
            .bindNullable("recipientPhone", order.recipient.phone, String::class.java)
            .bind("recipientChannels", order.recipient.channels.joinToString(CHANNEL_SEPARATOR) { it.wire })
            .bindNullable("paymentAttemptId", order.paymentAttemptId?.value, UUID::class.java)
            .bindNullable("paidAt", order.paidAt, Instant::class.java)
            .bindNullable("paymentExpiresAt", order.paymentExpiresAt, Instant::class.java)
            .bindNullable("refundId", order.refund?.refundId?.value, UUID::class.java)
            .bindNullable("refundRecordedAt", order.refund?.recordedAt, Instant::class.java)
    }

    fun bindChange(
        spec: GenericExecuteSpec,
        orderId: UUID,
        position: Int,
        change: StatusChange,
    ): GenericExecuteSpec =
        spec
            .bind("orderId", orderId)
            .bind("position", position)
            .bind("kind", change.kind.wire)
            .bindNullable("fromStatus", change.from, String::class.java)
            .bind("toStatus", change.status)
            .bind("changedAt", change.at)
            .bind("changedBy", change.by)
            .bindNullable("reason", change.reason?.name, String::class.java)

    fun head(row: Readable): OrderHead {
        val id = row.required<UUID>("id")
        val currency = row.required<String>("currency").trim()
        val reason = row.optional<String>("cancellation_reason")?.let(CancellationReason::valueOf)
        val cancellation =
            reason?.let { Cancellation(it, row.required("cancelled_at"), row.required("cancelled_by")) }
        val refund = row.optional<UUID>("refund_id")?.let { Refund(RefundId(it), row.required("refund_recorded_at")) }
        val fields =
            OrderFields(
                id = OrderId(id),
                number = OrderNumber(row.required("order_number")),
                accountId = AccountId(row.required("account_id")),
                accountPseudonym = row.optional("account_pseudonym"),
                deliveryAddress = addressOf(row),
                recipient = recipientOf(row),
                paymentMethodRef = row.required("payment_method_ref"),
                idempotencyKey = IdempotencyKey(row.required("idempotency_key")),
                reservationId = ReservationId(row.required("reservation_id")),
                orderStatus = checkNotNull(OrderStatus.fromWire(row.required("order_status"))),
                paymentStatus = checkNotNull(PaymentStatus.fromWire(row.required("payment_status"))),
                cancellation = cancellation,
                declineCategory = row.optional<String>("decline_category")?.let(DeclineCategory::fromWire),
                paymentAttemptId = row.optional<UUID>("payment_attempt_id")?.let(::PaymentAttemptId),
                paidAt = row.optional("paid_at"),
                paymentExpiresAt = row.optional("payment_expires_at"),
                refund = refund,
                placedAt = row.required("placed_at"),
                version = row.required("version"),
            )
        check(currency.isNotEmpty()) { "order $id has no currency" }
        return OrderHead(id) { lines, history -> fields.toOrder(lines, history) }
    }

    fun lineOf(row: Readable): Pair<UUID, OrderLine> =
        row.required<UUID>("order_id") to
            OrderLine(
                ProductId(row.required("product_id")),
                row.required("sku"),
                row.required("name"),
                Money(row.required("unit_price_minor"), row.required<String>("currency").trim()),
                Quantity(row.required("quantity")),
            )

    fun changeOf(row: Readable): Pair<UUID, StatusChange> =
        row.required<UUID>("order_id") to
            StatusChange(
                kind = StatusKind.entries.first { it.wire == row.required<String>("kind") },
                from = row.optional("from_status"),
                status = row.required("to_status"),
                at = row.required("changed_at"),
                by = row.required("changed_by"),
                reason = row.optional<String>("reason")?.let(CancellationReason::valueOf),
            )

    private fun addressOf(row: Readable): DeliveryAddress =
        DeliveryAddress(
            recipientName = row.required("recipient_name"),
            line1 = row.required("address_line1"),
            line2 = row.optional("address_line2"),
            city = row.required("city"),
            region = row.optional("region"),
            postalCode = row.required("postal_code"),
            country = row.required<String>("country").trim(),
        )

    private fun recipientOf(row: Readable): Recipient =
        Recipient(
            email = row.required("recipient_email"),
            phone = row.optional("recipient_phone"),
            channels =
                row
                    .required<String>("recipient_channels")
                    .split(CHANNEL_SEPARATOR)
                    .mapNotNull(Channel::fromWire),
        )
}

/** The scalar fields of an order row, waiting for its lines and history. */
private data class OrderFields(
    val id: OrderId,
    val number: OrderNumber,
    val accountId: AccountId,
    val accountPseudonym: String?,
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
    val version: Long,
) {
    fun toOrder(
        lines: List<OrderLine>,
        history: List<StatusChange>,
    ): Order =
        Order(
            id = id,
            number = number,
            accountId = accountId,
            accountPseudonym = accountPseudonym,
            lines = lines,
            deliveryAddress = deliveryAddress,
            recipient = recipient,
            paymentMethodRef = paymentMethodRef,
            idempotencyKey = idempotencyKey,
            reservationId = reservationId,
            orderStatus = orderStatus,
            paymentStatus = paymentStatus,
            cancellation = cancellation,
            declineCategory = declineCategory,
            paymentAttemptId = paymentAttemptId,
            paidAt = paidAt,
            paymentExpiresAt = paymentExpiresAt,
            refund = refund,
            placedAt = placedAt,
            history = history,
            version = version,
        )
}
