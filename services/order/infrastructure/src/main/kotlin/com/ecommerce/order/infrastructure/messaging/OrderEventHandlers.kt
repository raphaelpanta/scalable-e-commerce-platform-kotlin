package com.ecommerce.order.infrastructure.messaging

import arrow.core.Either
import com.ecommerce.order.application.AnonymiseAccountOrders
import com.ecommerce.order.application.ApplyPaymentOutcome
import com.ecommerce.order.application.RecordRefund
import com.ecommerce.order.domain.AccountId
import com.ecommerce.order.domain.DeclineCategory
import com.ecommerce.order.domain.OrderError
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.PaymentAttemptId
import com.ecommerce.order.domain.PaymentOutcome
import com.ecommerce.order.domain.RefundId
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.envelope.ReceivedEnvelope
import com.ecommerce.platform.messaging.envelope.eventType
import com.ecommerce.platform.messaging.envelope.payloadAs
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID

/** `PaymentOutcomePayload` of events.yaml (the fields the order reads). */
data class PaymentOutcomeEvent(
    val paymentId: UUID,
    val orderId: UUID,
    val reasonCategory: String? = null,
)

/** `RefundRecordedPayload` (the fields the order reads). */
data class RefundRecordedEvent(
    val refundId: UUID,
    val orderId: UUID,
)

/** `AccountDeletedPayload`. */
data class AccountDeletedEvent(
    val accountId: UUID,
    val pseudonym: String,
)

/** What handling one event did. */
enum class Handling {
    APPLIED,
    UNCHANGED,
    IGNORED,
}

/** Thrown when an event lost the optimistic lock repeatedly; the listener container retries the record. */
class EventRetryException(
    message: String,
) : RuntimeException(message)

/**
 * The order's event consumers (group `order`): payment outcomes and refunds from `payment.payment.v1`, account
 * deletion from `identity.account.v1`. Each runs inside the idempotent consumer of platform-messaging, so its
 * changes commit with the processed-event marker; derived events carry the incoming correlation id.
 */
class OrderEventHandlers(
    private val applyPaymentOutcome: ApplyPaymentOutcome,
    private val recordRefund: RecordRefund,
    private val anonymiseAccountOrders: AnonymiseAccountOrders,
) {
    /** `PaymentApproved`, `PaymentDeclined`, `PaymentPending`, `RefundRecorded`; other types are ignored. */
    suspend fun onPaymentEvent(envelope: ReceivedEnvelope): Handling =
        withCorrelationId(envelope.correlationId) {
            when (envelope.eventType()) {
                EventType.PaymentApproved -> {
                    payment(
                        envelope,
                    ) { PaymentOutcome.Approved(PaymentAttemptId(it.paymentId)) }
                }

                EventType.PaymentDeclined -> {
                    payment(envelope) {
                        PaymentOutcome.Declined(PaymentAttemptId(it.paymentId), categoryOf(it))
                    }
                }

                EventType.PaymentPending -> {
                    payment(envelope) { PaymentOutcome.Pending(PaymentAttemptId(it.paymentId)) }
                }

                EventType.RefundRecorded -> {
                    refund(envelope)
                }

                else -> {
                    Handling.IGNORED
                }
            }
        }

    /** `AccountDeleted`; other account events are ignored. */
    suspend fun onAccountEvent(envelope: ReceivedEnvelope): Handling =
        withCorrelationId(envelope.correlationId) {
            if (envelope.eventType() == EventType.AccountDeleted) {
                val payload = envelope.payloadAs<AccountDeletedEvent>()
                outcome(envelope, anonymiseAccountOrders(AccountId(payload.accountId), payload.pseudonym)) { it > 0 }
            } else {
                Handling.IGNORED
            }
        }

    private suspend fun payment(
        envelope: ReceivedEnvelope,
        outcomeOf: (PaymentOutcomeEvent) -> PaymentOutcome,
    ): Handling {
        val payload = envelope.payloadAs<PaymentOutcomeEvent>()
        return outcome(envelope, applyPaymentOutcome(OrderId(payload.orderId), outcomeOf(payload))) { it.changed }
    }

    private suspend fun refund(envelope: ReceivedEnvelope): Handling {
        val payload = envelope.payloadAs<RefundRecordedEvent>()
        return outcome(envelope, recordRefund(OrderId(payload.orderId), RefundId(payload.refundId))) { it.changed }
    }

    private fun <T> outcome(
        envelope: ReceivedEnvelope,
        result: Either<OrderError, T>,
        changed: (T) -> Boolean,
    ): Handling =
        when (result) {
            is Either.Right -> {
                if (changed(result.value)) Handling.APPLIED else Handling.UNCHANGED
            }

            is Either.Left -> {
                if (result.value == OrderError.ConcurrentUpdate) {
                    throw EventRetryException("event ${envelope.eventId} lost the optimistic lock")
                }
                log.info("Event {} ({}) ignored: {}", envelope.eventId, envelope.type, result.value)
                Handling.IGNORED
            }
        }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(OrderEventHandlers::class.java)

        fun categoryOf(payload: PaymentOutcomeEvent): DeclineCategory =
            payload.reasonCategory?.let(DeclineCategory::fromWire) ?: DeclineCategory.CARD_REJECTED
    }
}
