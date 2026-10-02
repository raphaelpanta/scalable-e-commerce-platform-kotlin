package com.ecommerce.payment.infrastructure.messaging

import arrow.core.Either
import arrow.core.raise.either
import com.ecommerce.payment.application.CancellationRefund
import com.ecommerce.payment.application.CancelledOrder
import com.ecommerce.payment.application.ChargePlacedOrder
import com.ecommerce.payment.application.PlacedOrderCharge
import com.ecommerce.payment.application.RefundCancelledOrder
import com.ecommerce.payment.domain.AccountId
import com.ecommerce.payment.domain.ChargeRequest
import com.ecommerce.payment.domain.IdempotencyKey
import com.ecommerce.payment.domain.Money
import com.ecommerce.payment.domain.OrderId
import com.ecommerce.payment.domain.PaymentAttemptId
import com.ecommerce.payment.domain.PaymentError
import com.ecommerce.payment.domain.PaymentMethodRef
import com.ecommerce.payment.domain.Recipient
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.envelope.ReceivedEnvelope
import com.ecommerce.platform.messaging.envelope.eventType
import com.ecommerce.platform.messaging.envelope.payloadAs
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID

/** `Money` of events.yaml. */
data class MoneyEvent(
    val amountMinor: Long,
    val currency: String,
)

/** `RecipientSnapshot` of events.yaml (personal data, never logged). */
data class RecipientEvent(
    val accountId: UUID? = null,
    val email: String,
    val phone: String? = null,
    val preferredChannels: List<String> = emptyList(),
)

/** The members of `OrderPlacedPayload` the payment context reads. */
data class OrderPlacedEvent(
    val orderId: UUID,
    val accountId: UUID,
    val total: MoneyEvent,
    val paymentMethodRef: String,
    val idempotencyKey: String,
)

/** The members of `OrderCancelledPayload` the payment context reads. */
data class OrderCancelledEvent(
    val orderId: UUID,
    val accountId: UUID,
    val paymentStatus: String,
    val refundRequired: Boolean = false,
    val paymentId: UUID? = null,
    val recipient: RecipientEvent,
)

/** What handling one event did. */
enum class Handling {
    APPLIED,
    UNCHANGED,
    IGNORED,
}

/**
 * The payment context's consumers of `order.order.v1` (group `payment`): `OrderPlaced` charges the order under its
 * checkout key (converging with the synchronous charge), `OrderCancelled` with an approved payment records the
 * refund. Each runs inside the idempotent consumer of platform-messaging, so its changes commit with the
 * processed-event marker, and the events it publishes carry the incoming correlation id. Other types are ignored.
 */
class PaymentEventHandlers(
    private val chargePlacedOrder: ChargePlacedOrder,
    private val refundCancelledOrder: RefundCancelledOrder,
) {
    suspend fun onOrderEvent(envelope: ReceivedEnvelope): Handling =
        withCorrelationId(envelope.correlationId) {
            when (envelope.eventType()) {
                EventType.OrderPlaced -> orderPlaced(envelope)
                EventType.OrderCancelled -> orderCancelled(envelope)
                else -> Handling.IGNORED
            }
        }

    private suspend fun orderPlaced(envelope: ReceivedEnvelope): Handling =
        when (val request = chargeOf(envelope.payloadAs<OrderPlacedEvent>())) {
            is Either.Left -> {
                ignored(envelope, request.value)
            }

            is Either.Right -> {
                when (val outcome = chargePlacedOrder(request.value)) {
                    is PlacedOrderCharge.Charged -> Handling.APPLIED
                    is PlacedOrderCharge.Converged -> Handling.UNCHANGED
                    is PlacedOrderCharge.Refused -> ignored(envelope, outcome.error)
                }
            }
        }

    private suspend fun orderCancelled(envelope: ReceivedEnvelope): Handling {
        val payload = envelope.payloadAs<OrderCancelledEvent>()
        val order =
            CancelledOrder(
                orderId = OrderId(payload.orderId),
                refundRequired = payload.refundRequired || payload.paymentStatus == APPROVED,
                paymentId = payload.paymentId?.let(::PaymentAttemptId),
                recipient = recipientOf(payload),
            )
        return when (val outcome = refundCancelledOrder(order)) {
            is CancellationRefund.Refunded, is CancellationRefund.Announced -> Handling.APPLIED
            is CancellationRefund.AlreadyRefunded -> Handling.UNCHANGED
            is CancellationRefund.Refused -> ignored(envelope, outcome.error)
            CancellationRefund.NoApprovedCharge -> ignored(envelope, "no approved charge to refund")
            CancellationRefund.NotRequired -> Handling.IGNORED
        }
    }

    private fun ignored(
        envelope: ReceivedEnvelope,
        reason: Any,
    ): Handling {
        log.info("Event {} ({}) ignored: {}", envelope.eventId, envelope.type, reason)
        return Handling.IGNORED
    }

    private companion object {
        const val APPROVED = "approved"
        val log: Logger = LoggerFactory.getLogger(PaymentEventHandlers::class.java)

        fun chargeOf(payload: OrderPlacedEvent): Either<PaymentError, ChargeRequest> =
            either {
                val key =
                    runCatching { UUID.fromString(payload.idempotencyKey) }.getOrNull()
                        ?: raise(PaymentError.Invalid("idempotencyKey", "must be a UUID"))
                ChargeRequest(
                    OrderId(payload.orderId),
                    AccountId(payload.accountId),
                    Money.positive(payload.total.amountMinor, payload.total.currency, "total").bind(),
                    PaymentMethodRef.of(payload.paymentMethodRef).bind(),
                    IdempotencyKey(key),
                )
            }

        fun recipientOf(payload: OrderCancelledEvent): Recipient =
            payload.recipient.let {
                Recipient(AccountId(it.accountId ?: payload.accountId), it.email, it.phone, it.preferredChannels)
            }
    }
}
