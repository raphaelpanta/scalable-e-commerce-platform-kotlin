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

/** Message used when identity still cannot tell the contact of a notification awaiting its recipient. */
internal const val CONTACT_UNAVAILABLE = "The recipient's contact details are unavailable."

/** Message used when identity no longer knows the account of a notification awaiting its recipient. */
internal const val UNKNOWN_ACCOUNT = "The recipient account is unknown."

/**
 * One delivery round (T091, FR-019): claims the queued notifications that are due, hands each to its channel and
 * records the outcome. The send happens outside any transaction; the new status, the attempt row and the
 * `NotificationSent`/`NotificationFailed` event are then written in one transaction, and only while the
 * notification is still `queued` (a concurrent suppression wins). Returns the number of notifications claimed.
 *
 * A notification queued while identity could not answer ([Notification.awaitingRecipient], T158) first has its
 * contact looked up again ([contacts]): it is then sent, or suppressed when the contact does not permit its channel;
 * while identity still cannot answer, the lookup counts as a failed attempt of the retry schedule, so the message
 * ends `failed` (visible to operators, who may retry it) rather than lost.
 */
@Suppress("LongParameterList") // one port per collaborator of a delivery round, plus the settings
class AttemptDelivery(
    private val notifications: NotificationRepository,
    private val channels: Channels,
    private val contacts: RecipientLookupPort,
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

    private suspend fun deliver(claimed: Notification) {
        if (!claimed.awaitingRecipient) return attempt(claimed, claimed)
        when (val lookup = contacts.lookup(claimed.accountId, claimed.correlationId)) {
            is ContactLookup.Found -> {
                claimed.resolveRecipient(lookup.contact).onRight { resolved ->
                    if (resolved.status == DeliveryStatus.QUEUED) {
                        attempt(claimed, resolved)
                    } else {
                        record(claimed, resolved, clock.now())
                    }
                }
            }

            ContactLookup.Unknown -> {
                fail(claimed, DeliveryFailure(FailureCategory.INVALID_RECIPIENT, UNKNOWN_ACCOUNT))
            }

            ContactLookup.Unavailable -> {
                fail(claimed, DeliveryFailure(FailureCategory.CHANNEL_UNAVAILABLE, CONTACT_UNAVAILABLE))
            }
        }
    }

    /** Sends [ready] ([claimed] with its recipient) and records the outcome against the claimed state. */
    private suspend fun attempt(
        claimed: Notification,
        ready: Notification,
    ) {
        val result = send(ready)
        val at = clock.now()
        val next =
            when (result) {
                SendResult.Delivered -> ready.recordSuccess(at)
                is SendResult.Failed -> ready.recordFailure(at, result.failure, settings.policy)
            }
        next.onRight { updated -> record(claimed, updated, at) }
    }

    /** Records an attempt of [claimed] that failed before reaching a channel. */
    private suspend fun fail(
        claimed: Notification,
        failure: DeliveryFailure,
    ) {
        val at = clock.now()
        claimed.recordFailure(at, failure, settings.policy).onRight { updated -> record(claimed, updated, at) }
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
