package com.ecommerce.notification.application

import com.ecommerce.notification.domain.AccountId
import com.ecommerce.notification.domain.DedupeKey
import com.ecommerce.notification.domain.DeliveryAttempt
import com.ecommerce.notification.domain.DeliveryFailure
import com.ecommerce.notification.domain.DeliveryStatus
import com.ecommerce.notification.domain.EventId
import com.ecommerce.notification.domain.MessageContent
import com.ecommerce.notification.domain.Notification
import com.ecommerce.notification.domain.NotificationChannel
import com.ecommerce.notification.domain.NotificationId
import com.ecommerce.notification.domain.NotificationKind
import com.ecommerce.notification.domain.Recipient
import com.ecommerce.notification.domain.RecipientAddress
import com.ecommerce.notification.domain.RecipientContact
import java.time.Instant

/** The current time (UTC). */
fun interface Clock {
    fun now(): Instant
}

/** Runs [block] in one database transaction: its writes, outbox events included, commit together or not at all. */
interface Transactions {
    suspend fun <T> run(block: suspend () -> T): T
}

/** A page of a listing: 0-based [page], [size] 1..100 (validated by the web layer). */
data class PageRequest(
    val page: Int,
    val size: Int,
) {
    val offset: Long get() = page.toLong() * size
}

/** One page of results and the total number of matches. */
data class Page<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
)

/** Filters of the shopper's own history (`listOwnNotifications`). */
data class OwnFilter(
    val accountId: AccountId,
    val channel: NotificationChannel? = null,
    val kind: NotificationKind? = null,
)

/** Filters of the operator's failed-delivery view (`listFailedNotifications`); [failedTo] is exclusive. */
data class FailedFilter(
    val accountId: AccountId? = null,
    val channel: NotificationChannel? = null,
    val kind: NotificationKind? = null,
    val failedFrom: Instant? = null,
    val failedTo: Instant? = null,
)

/** Notifications and their attempt history. */
interface NotificationRepository {
    /** The keys of the notifications already produced for [eventId]. */
    suspend fun keysForEvent(eventId: EventId): Set<DedupeKey>

    /** Stores [notification] unless one with the same [DedupeKey] exists; true when stored. */
    suspend fun insertIfAbsent(notification: Notification): Boolean

    suspend fun findById(id: NotificationId): Notification?

    /** Replaces the stored state with [notification] when the stored status is still [expected]; true when done. */
    suspend fun update(
        notification: Notification,
        expected: DeliveryStatus,
    ): Boolean

    /**
     * Claims up to [limit] queued notifications due at [now] (oldest first) for one delivery round: their next
     * attempt moves to [leaseUntil], so that no other instance picks them before this round records the result.
     */
    suspend fun claimDue(
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
    ): List<Notification>

    /** Appends [attempt] to the history (`delivery_attempts`). */
    suspend fun recordAttempt(attempt: DeliveryAttempt)

    /** The queued notifications of [accountId]. */
    suspend fun queuedFor(accountId: AccountId): List<Notification>

    /** Clears the recipient addresses and message bodies of every notification of [accountId] (FR-007). */
    suspend fun forgetRecipient(accountId: AccountId)

    /** The shopper's notifications, newest first. */
    suspend fun listOwn(
        filter: OwnFilter,
        page: PageRequest,
    ): Page<Notification>

    /** Failed notifications across accounts, newest failure first. */
    suspend fun listFailed(
        filter: FailedFilter,
        page: PageRequest,
    ): Page<Notification>
}

/** The retention side of the notification store (data-model section 5). */
fun interface NotificationRetentionRepository {
    /**
     * Deletes up to [limit] terminal notifications (`sent`, `failed`, `suppressed`) created before [cutoff], with
     * their attempt history; `queued` ones are never deleted. Returns how many notifications were deleted.
     */
    suspend fun deleteTerminalCreatedBefore(
        cutoff: Instant,
        limit: Int,
    ): Int
}

/** The recipient read model (data-model §3.6 `Recipient`). */
interface RecipientReadModel {
    suspend fun find(accountId: AccountId): Recipient?

    suspend fun save(recipient: Recipient)
}

/** What identity says about an account's contact details (`GET /internal/accounts/{accountId}/contact`). */
sealed interface ContactLookup {
    /** The contact details and permitted channels now. */
    data class Found(
        val contact: RecipientContact,
    ) : ContactLookup

    /** Identity does not know the account (404). */
    data object Unknown : ContactLookup

    /** Identity could not answer (unreachable, timeout, 5xx). */
    data object Unavailable : ContactLookup
}

/** Reads the current contact details and preferences of an account (FR-020: they apply to the next event). */
fun interface RecipientLookupPort {
    suspend fun lookup(
        accountId: AccountId,
        correlationId: String,
    ): ContactLookup
}

/** One message handed to a channel. */
data class OutgoingMessage(
    val notificationId: NotificationId,
    val to: RecipientAddress,
    val content: MessageContent,
)

/** The outcome of handing a message to a channel. */
sealed interface SendResult {
    data object Delivered : SendResult

    data class Failed(
        val failure: DeliveryFailure,
    ) : SendResult
}

/** The email channel (SMTP; Mailpit locally). Adapters report failures as [SendResult.Failed], never throw. */
fun interface EmailSenderPort {
    suspend fun send(message: OutgoingMessage): SendResult
}

/** The SMS channel (simulated). Adapters report failures as [SendResult.Failed], never throw. */
fun interface SmsSenderPort {
    suspend fun send(message: OutgoingMessage): SendResult
}

/** Publishes `NotificationSent` and `NotificationFailed` (through the outbox, inside the caller's transaction). */
interface DeliveryOutcomePublisher {
    suspend fun sent(notification: Notification)

    suspend fun failed(notification: Notification)
}
