package com.ecommerce.notification.domain

import java.time.Instant

/** The consumed event a message answers: its id (deduplication key), recipient account and correlation id. */
data class EventSource(
    val eventId: EventId,
    val accountId: AccountId,
    val correlationId: String,
)

/**
 * Turns one consumed event into its notifications (FR-018, FR-019, data-model §3.6 invariants):
 * - channels come from [ChannelSelection] on the contact read now (preferences apply immediately, FR-020);
 * - a recipient anonymised in the read model receives nothing, whatever the contact says (FR-007): its message
 *   is recorded once as `suppressed`;
 * - one notification per channel and event: keys already produced for the event ([alreadyProduced]) are skipped,
 *   so a redelivered event yields nothing new;
 * - delivered channels start `queued` and are due at once; suppressed ones keep only the subject.
 */
object NotificationPlanner {
    /** The new notifications for [data] from [source] at [at]. */
    @Suppress("LongParameterList") // one value per input of the planning rule
    fun plan(
        source: EventSource,
        data: TemplateData,
        contact: RecipientContact,
        recipient: Recipient?,
        alreadyProduced: Set<DedupeKey>,
        at: Instant,
    ): List<Notification> {
        val effective = if (recipient?.anonymised == true) RecipientContact.ANONYMISED else contact
        return ChannelSelection
            .select(data.kind, effective)
            .map { decision -> notificationFor(source, data, decision, at) }
            .filterNot { it.dedupeKey in alreadyProduced }
    }

    /**
     * The message for [data] when identity could not tell the contact details and the event carried no snapshot: one
     * `queued` email without an address, [Notification.awaitingRecipient], due at once. The delivery job resolves the
     * recipient before sending (and suppresses it when the contact turns out not to permit email), so the event is
     * never lost (FR-018, FR-019). Email only: it is the default channel and the only one of the security kinds; SMS
     * needs preferences that could not be read.
     */
    fun deferred(
        source: EventSource,
        data: TemplateData,
        at: Instant,
    ): Notification =
        Notification(
            id = NotificationId.random(),
            sourceEventId = source.eventId,
            kind = data.kind,
            channel = NotificationChannel.EMAIL,
            accountId = source.accountId,
            recipient = null,
            content = Templates.render(data, NotificationChannel.EMAIL),
            orderId = Templates.orderOf(data),
            correlationId = source.correlationId,
            status = DeliveryStatus.QUEUED,
            attempts = 0,
            createdAt = at,
            nextAttemptAt = at,
            awaitingRecipient = true,
        )

    private fun notificationFor(
        source: EventSource,
        data: TemplateData,
        decision: ChannelDecision,
        at: Instant,
    ): Notification {
        val content = Templates.render(data, decision.channel)
        val deliver = decision as? ChannelDecision.Deliver
        return Notification(
            id = NotificationId.random(),
            sourceEventId = source.eventId,
            kind = data.kind,
            channel = decision.channel,
            accountId = source.accountId,
            recipient = deliver?.address,
            content = if (deliver == null) content.copy(body = "") else content,
            orderId = Templates.orderOf(data),
            correlationId = source.correlationId,
            status = if (deliver == null) DeliveryStatus.SUPPRESSED else DeliveryStatus.QUEUED,
            attempts = 0,
            createdAt = at,
            nextAttemptAt = if (deliver == null) null else at,
        )
    }
}
