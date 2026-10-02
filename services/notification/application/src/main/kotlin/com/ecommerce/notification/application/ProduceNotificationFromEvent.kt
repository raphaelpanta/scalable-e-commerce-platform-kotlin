package com.ecommerce.notification.application

import com.ecommerce.notification.domain.AccountId
import com.ecommerce.notification.domain.EventSource
import com.ecommerce.notification.domain.NotificationPlanner
import com.ecommerce.notification.domain.Recipient
import com.ecommerce.notification.domain.RecipientContact
import com.ecommerce.notification.domain.TemplateData

/** A consumed event, as the notification service understands it (events.yaml payloads, mapped by the adapter). */
sealed interface NotificationTrigger {
    val source: EventSource

    /** `AccountRegistered`: the recipient appears in the read model and receives the verification message. */
    data class AccountRegistered(
        override val source: EventSource,
        val snapshot: RecipientContact?,
        val verification: TemplateData.AccountVerification,
    ) : NotificationTrigger

    /** `AccountVerified`: the read model records the verified address; no message. */
    data class AccountVerified(
        override val source: EventSource,
    ) : NotificationTrigger

    /** `AccountDeleted`: stop messaging the account and forget its addresses (FR-007). */
    data class AccountDeleted(
        override val source: EventSource,
    ) : NotificationTrigger

    /** Every other consumed event: one message described by [data]; [snapshot] is the event's recipient copy. */
    data class MessageRequested(
        override val source: EventSource,
        val snapshot: RecipientContact?,
        val data: TemplateData,
    ) : NotificationTrigger
}

/** Identity could not answer and the event carries no recipient snapshot: the event must be retried. */
class RecipientUnavailableException(
    accountId: AccountId,
) : RuntimeException("contact details of account $accountId are unavailable")

/**
 * Produces the notifications of one consumed event (T091). Runs inside the idempotent consumer's transaction, so
 * a redelivered `eventId` never reaches it; it also skips an event that already produced notifications (FR-019).
 * Contact details come from identity ([RecipientLookupPort]); when identity cannot answer, the recipient snapshot
 * of the event is used instead. Returns the number of notifications created.
 */
class ProduceNotificationFromEvent(
    private val notifications: NotificationRepository,
    private val recipients: RecipientReadModel,
    private val contacts: RecipientLookupPort,
    private val clock: Clock,
) {
    suspend operator fun invoke(trigger: NotificationTrigger): Int =
        when (trigger) {
            is NotificationTrigger.AccountRegistered -> {
                val accountId = trigger.source.accountId
                if (recipients.find(accountId) == null) recipients.save(Recipient.registered(accountId))
                produce(trigger.source, trigger.snapshot, trigger.verification)
            }

            is NotificationTrigger.AccountVerified -> {
                recipients.save(recipientOf(trigger.source.accountId).verified())
                0
            }

            is NotificationTrigger.AccountDeleted -> {
                stopMessaging(trigger.source.accountId)
                0
            }

            is NotificationTrigger.MessageRequested -> {
                produce(trigger.source, trigger.snapshot, trigger.data)
            }
        }

    private suspend fun produce(
        source: EventSource,
        snapshot: RecipientContact?,
        data: TemplateData,
    ): Int {
        val existing = notifications.keysForEvent(source.eventId)
        if (existing.isNotEmpty()) return 0
        val recipient = recipients.find(source.accountId)
        val contact = if (recipient?.anonymised == true) RecipientContact.ANONYMISED else contactOf(source, snapshot)
        val planned =
            contact?.let { NotificationPlanner.plan(source, data, it, recipient, existing, clock.now()) }.orEmpty()
        var created = 0
        for (notification in planned) {
            if (notifications.insertIfAbsent(notification)) created++
        }
        return created
    }

    private suspend fun contactOf(
        source: EventSource,
        snapshot: RecipientContact?,
    ): RecipientContact? =
        when (val lookup = contacts.lookup(source.accountId, source.correlationId)) {
            is ContactLookup.Found -> lookup.contact
            ContactLookup.Unknown -> null
            ContactLookup.Unavailable -> snapshot ?: throw RecipientUnavailableException(source.accountId)
        }

    private suspend fun stopMessaging(accountId: AccountId) {
        recipients.save(recipientOf(accountId).anonymise())
        notifications.queuedFor(accountId).forEach { queued ->
            queued.suppress().onRight { notifications.update(it, queued.status) }
        }
        notifications.forgetRecipient(accountId)
    }

    private suspend fun recipientOf(accountId: AccountId): Recipient =
        recipients.find(accountId) ?: Recipient.registered(accountId)
}
