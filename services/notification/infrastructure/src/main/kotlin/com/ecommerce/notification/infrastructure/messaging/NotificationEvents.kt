package com.ecommerce.notification.infrastructure.messaging

import com.ecommerce.notification.application.DeliveryOutcomePublisher
import com.ecommerce.notification.application.NotificationTrigger
import com.ecommerce.notification.application.ProduceNotificationFromEvent
import com.ecommerce.notification.domain.AccountId
import com.ecommerce.notification.domain.EventId
import com.ecommerce.notification.domain.EventSource
import com.ecommerce.notification.domain.Notification
import com.ecommerce.notification.domain.OrderId
import com.ecommerce.notification.domain.SecretLink
import com.ecommerce.notification.domain.TemplateData
import com.ecommerce.platform.messaging.consumer.EventListenerSupport
import com.ecommerce.platform.messaging.envelope.EnvelopeFactory
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.envelope.ReceivedEnvelope
import com.ecommerce.platform.messaging.envelope.Topic
import com.ecommerce.platform.messaging.envelope.payloadAs
import com.ecommerce.platform.messaging.outbox.OutboxPublisher
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import java.util.UUID

/** Paths of the links in identity messages, under `PUBLIC_BASE_URL`. */
object LinkPaths {
    const val VERIFY = "/verify"
    const val RESET_PASSWORD = "/reset-password"
}

/**
 * Maps a consumed envelope to the [NotificationTrigger] it stands for (events.yaml payloads); `null` for the event
 * types of the three topics this service does not act on (`OrderPlaced`, `PaymentApproved`, ...). Tokens become
 * links under [publicBaseUrl] and never leave the [TemplateData] (whose `toString()` masks them).
 */
class EventTriggers(
    private val publicBaseUrl: String,
) {
    fun of(envelope: ReceivedEnvelope): NotificationTrigger? =
        when (envelope.type) {
            EventType.AccountRegistered.name -> accountRegistered(envelope)
            EventType.AccountVerified.name -> NotificationTrigger.AccountVerified(source(envelope, account(envelope)))
            EventType.AccountDeleted.name -> NotificationTrigger.AccountDeleted(source(envelope, account(envelope)))
            EventType.PasswordResetRequested.name -> passwordReset(envelope)
            EventType.OrderPaid.name -> orderPaid(envelope)
            EventType.OrderPaymentFailed.name -> paymentFailed(envelope)
            EventType.OrderShipped.name -> orderStatus(envelope) { TemplateData.OrderShipped(it) }
            EventType.OrderDelivered.name -> orderStatus(envelope) { TemplateData.OrderDelivered(it) }
            EventType.OrderCancelled.name -> orderCancelled(envelope)
            EventType.RefundRecorded.name -> refundRecorded(envelope)
            else -> null
        }

    private fun accountRegistered(envelope: ReceivedEnvelope): NotificationTrigger {
        val payload = envelope.payloadAs<AccountRegisteredPayload>()
        val link = SecretLink.of(publicBaseUrl, LinkPaths.VERIFY, payload.verificationToken)
        return NotificationTrigger.AccountRegistered(
            source(envelope, payload.accountId),
            payload.recipient?.toContact(),
            TemplateData.AccountVerification(link),
        )
    }

    private fun passwordReset(envelope: ReceivedEnvelope): NotificationTrigger {
        val payload = envelope.payloadAs<PasswordResetRequestedPayload>()
        val link = SecretLink.of(publicBaseUrl, LinkPaths.RESET_PASSWORD, payload.resetToken)
        return message(envelope, payload.accountId, payload.recipient, TemplateData.PasswordReset(link))
    }

    private fun orderPaid(envelope: ReceivedEnvelope): NotificationTrigger {
        val payload = envelope.payloadAs<OrderPaidPayload>()
        val data =
            TemplateData.OrderConfirmation(
                payload.order,
                payload.lines.map(OrderLinePayload::toLine),
                payload.total.toAmount(),
                payload.deliveryAddress.toAddress(),
            )
        return message(envelope, payload.accountId, payload.recipient, data)
    }

    private fun paymentFailed(envelope: ReceivedEnvelope): NotificationTrigger {
        val payload = envelope.payloadAs<OrderPaymentFailedPayload>()
        val data = TemplateData.PaymentFailure(payload.order, payload.total.toAmount(), payload.reasonCategory)
        return message(envelope, payload.accountId, payload.recipient, data)
    }

    private fun orderStatus(
        envelope: ReceivedEnvelope,
        data: (com.ecommerce.notification.domain.OrderReference) -> TemplateData,
    ): NotificationTrigger {
        val payload = envelope.payloadAs<OrderStatusChangedPayload>()
        return message(envelope, payload.accountId, payload.recipient, data(payload.order))
    }

    private fun orderCancelled(envelope: ReceivedEnvelope): NotificationTrigger {
        val payload = envelope.payloadAs<OrderCancelledPayload>()
        val data =
            TemplateData.OrderCancelled(payload.order, payload.total.toAmount(), payload.reason, payload.refundRequired)
        return message(envelope, payload.accountId, payload.recipient, data)
    }

    private fun refundRecorded(envelope: ReceivedEnvelope): NotificationTrigger {
        val payload = envelope.payloadAs<RefundRecordedPayload>()
        val data = TemplateData.RefundConfirmation(OrderId(payload.orderId), payload.amount.toAmount())
        return message(envelope, payload.accountId, payload.recipient, data)
    }

    private fun message(
        envelope: ReceivedEnvelope,
        accountId: UUID,
        recipient: RecipientSnapshotPayload?,
        data: TemplateData,
    ): NotificationTrigger =
        NotificationTrigger.MessageRequested(source(envelope, accountId), recipient?.toContact(), data)

    private fun account(envelope: ReceivedEnvelope): UUID = envelope.payloadAs<AccountPayload>().accountId

    private fun source(
        envelope: ReceivedEnvelope,
        accountId: UUID,
    ): EventSource = EventSource(EventId(envelope.eventId), AccountId(accountId), envelope.correlationId)
}

/** Handles one consumed envelope: maps it and produces its notifications (inside the idempotent transaction). */
class NotificationEventHandler(
    private val triggers: EventTriggers,
    private val produce: ProduceNotificationFromEvent,
) {
    /** The number of notifications the envelope produced (0 for an ignored type). */
    suspend fun handle(envelope: ReceivedEnvelope): Int {
        val trigger = triggers.of(envelope) ?: return 0
        val created = produce(trigger)
        log.info(
            "Event {} {} (correlation {}) produced {} notification(s)",
            envelope.type,
            envelope.eventId,
            envelope.correlationId,
            created,
        )
        return created
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(NotificationEventHandler::class.java)
    }
}

/**
 * The Kafka listener of the service (consumer group `notification`): account, order and payment events, each
 * handled once per `eventId` through [EventListenerSupport] (idempotent consumer, retries, dead-letter topic).
 */
class NotificationEventListener(
    private val events: EventListenerSupport,
    private val handler: NotificationEventHandler,
) {
    @KafkaListener(topics = [Topic.ACCOUNT, Topic.ORDER, Topic.PAYMENT])
    fun onEvent(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        events.dispatch(record, ack) { envelope -> handler.handle(envelope) }
    }
}

/** Publishes `NotificationSent` / `NotificationFailed` (`NotificationOutcomePayload`) through the outbox. */
class OutboxDeliveryOutcomePublisher(
    private val outbox: OutboxPublisher,
    private val envelopes: EnvelopeFactory,
) : DeliveryOutcomePublisher {
    override suspend fun sent(notification: Notification) {
        publish(EventType.NotificationSent, notification, "sentAt" to notification.sentAt?.toString())
    }

    override suspend fun failed(notification: Notification) {
        publish(
            EventType.NotificationFailed,
            notification,
            "failedAt" to notification.failedAt?.toString(),
            "lastErrorCategory" to notification.lastFailure?.category?.name,
        )
    }

    private suspend fun publish(
        type: EventType,
        notification: Notification,
        vararg outcome: Pair<String, Any?>,
    ) {
        val payload =
            linkedMapOf<String, Any?>(
                "notificationId" to notification.id.toString(),
                "accountId" to notification.accountId.toString(),
                "channel" to notification.channel.wire,
                "template" to notification.kind.template,
                "sourceEventId" to notification.sourceEventId.toString(),
                "attempts" to notification.attempts,
            )
        outcome.forEach { (name, value) -> if (value != null) payload[name] = value }
        outbox.publish(envelopes.create(type, notification.id.value, notification.correlationId, payload))
    }
}
