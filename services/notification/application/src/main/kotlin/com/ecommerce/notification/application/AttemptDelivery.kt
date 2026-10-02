package com.ecommerce.notification.application

import com.ecommerce.notification.domain.DeliveryAttempt
import com.ecommerce.notification.domain.DeliveryFailure
import com.ecommerce.notification.domain.DeliveryStatus
import com.ecommerce.notification.domain.FailureCategory
import com.ecommerce.notification.domain.Notification
import com.ecommerce.notification.domain.NotificationChannel
import com.ecommerce.notification.domain.RetryPolicy
import java.time.Duration
import java.time.Instant

/** Message used when a queued notification has no address left (it cannot be delivered). */
internal const val NO_ADDRESS = "No recipient address is available."

/**
 * One delivery round (T091, FR-019): claims the queued notifications that are due, hands each to its channel and
 * records the outcome. The send happens outside any transaction; the new status, the attempt row and the
 * `NotificationSent`/`NotificationFailed` event are then written in one transaction, and only while the
 * notification is still `queued` (a concurrent suppression wins). Returns the number of notifications claimed.
 */
class AttemptDelivery(
    private val notifications: NotificationRepository,
    private val channels: Channels,
    private val outcomes: DeliveryOutcomePublisher,
    private val transactions: Transactions,
    private val clock: Clock,
    private val settings: Settings = Settings(),
) {
    /** The outbound channels. */
    data class Channels(
        val email: EmailSenderPort,
        val sms: SmsSenderPort,
    )

    /** The retry [policy] and the [lease] that keeps a claimed notification away from other instances. */
    data class Settings(
        val policy: RetryPolicy = RetryPolicy(),
        val lease: Duration = Duration.ofMinutes(2),
    )

    suspend fun deliverDue(limit: Int): Int {
        val now = clock.now()
        val claimed = transactions.run { notifications.claimDue(now, now.plus(settings.lease), limit) }
        claimed.forEach { deliver(it) }
        return claimed.size
    }

    private suspend fun deliver(notification: Notification) {
        val result = send(notification)
        val at = clock.now()
        val next =
            when (result) {
                SendResult.Delivered -> notification.recordSuccess(at)
                is SendResult.Failed -> notification.recordFailure(at, result.failure, settings.policy)
            }
        next.onRight { updated -> record(notification, updated, at) }
    }

    private suspend fun send(notification: Notification): SendResult {
        val to = notification.recipient
        return if (to == null) {
            SendResult.Failed(DeliveryFailure(FailureCategory.INVALID_RECIPIENT, NO_ADDRESS))
        } else {
            val message = OutgoingMessage(notification.id, to, notification.content)
            when (notification.channel) {
                NotificationChannel.EMAIL -> channels.email.send(message)
                NotificationChannel.SMS -> channels.sms.send(message)
            }
        }
    }

    private suspend fun record(
        before: Notification,
        after: Notification,
        at: Instant,
    ) {
        transactions.run {
            if (notifications.update(after, DeliveryStatus.QUEUED)) {
                DeliveryAttempt.between(before, after, at)?.let { notifications.recordAttempt(it) }
                when (after.status) {
                    DeliveryStatus.SENT -> outcomes.sent(after)
                    DeliveryStatus.FAILED -> outcomes.failed(after)
                    DeliveryStatus.QUEUED, DeliveryStatus.SUPPRESSED -> Unit
                }
            }
        }
    }
}
