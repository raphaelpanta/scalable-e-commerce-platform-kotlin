package com.ecommerce.notification.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant

/** A refused status transition: [action] is not allowed while the notification is [from] (data-model §3.6). */
data class TransitionRefused(
    val from: DeliveryStatus,
    val action: String,
)

/** The deduplication key of a notification: one per consumed event, kind and channel (data-model §3.6). */
data class DedupeKey(
    val sourceEventId: EventId,
    val kind: NotificationKind,
    val channel: NotificationChannel,
)

/**
 * One message to one recipient on one channel (data-model §3.6 `Notification`), with the delivery state machine:
 *
 * | From | Trigger | To |
 * |---|---|---|
 * | queued | attempt succeeds | sent |
 * | queued | attempt fails, budget left | queued, [nextAttemptAt] after the retry delay |
 * | queued | attempt fails, budget used or permanent failure | failed |
 * | queued | recipient anonymised | suppressed |
 * | failed | operator retry | queued, attempts reset |
 *
 * Every other transition is refused. [recipient] and [content] are personal data: `toString()` is a log-safe
 * summary without them.
 */
data class Notification(
    val id: NotificationId,
    val sourceEventId: EventId,
    val kind: NotificationKind,
    val channel: NotificationChannel,
    val accountId: AccountId,
    val recipient: RecipientAddress?,
    val content: MessageContent,
    val orderId: OrderId?,
    val correlationId: String,
    val status: DeliveryStatus,
    val attempts: Int,
    val createdAt: Instant,
    val nextAttemptAt: Instant? = null,
    val lastAttemptAt: Instant? = null,
    val lastFailure: DeliveryFailure? = null,
    val sentAt: Instant? = null,
    val failedAt: Instant? = null,
) {
    val dedupeKey: DedupeKey get() = DedupeKey(sourceEventId, kind, channel)

    /** The attempt just made succeeded at [at]. */
    fun recordSuccess(at: Instant): Either<TransitionRefused, Notification> =
        whenQueued("record a successful attempt") {
            copy(
                status = DeliveryStatus.SENT,
                attempts = attempts + 1,
                lastAttemptAt = at,
                nextAttemptAt = null,
                sentAt = at,
            )
        }

    /** The attempt just made failed at [at] with [failure]; [policy] decides between a retry and `failed`. */
    fun recordFailure(
        at: Instant,
        failure: DeliveryFailure,
        policy: RetryPolicy,
    ): Either<TransitionRefused, Notification> =
        whenQueued("record a failed attempt") {
            val made = attempts + 1
            if (failure.permanent || policy.exhausted(made)) {
                copy(
                    status = DeliveryStatus.FAILED,
                    attempts = made,
                    lastAttemptAt = at,
                    lastFailure = failure,
                    nextAttemptAt = null,
                    failedAt = at,
                )
            } else {
                copy(
                    attempts = made,
                    lastAttemptAt = at,
                    lastFailure = failure,
                    nextAttemptAt = at.plus(policy.delayAfter(made)),
                )
            }
        }

    /** The recipient may no longer be messaged (account deleted). */
    fun suppress(): Either<TransitionRefused, Notification> =
        whenQueued("suppress") { copy(status = DeliveryStatus.SUPPRESSED, nextAttemptAt = null) }

    /** An operator re-queues a failed notification at [at]: the retry budget starts again (notification.yaml). */
    fun requeue(at: Instant): Either<TransitionRefused, Notification> =
        if (status == DeliveryStatus.FAILED) {
            copy(
                status = DeliveryStatus.QUEUED,
                attempts = 0,
                nextAttemptAt = at,
                lastAttemptAt = null,
                lastFailure = null,
                failedAt = null,
            ).right()
        } else {
            TransitionRefused(status, "retry").left()
        }

    /** Log-safe summary: identifiers and state only, never the recipient or the content. */
    override fun toString(): String =
        "Notification(id=$id, kind=${kind.wire}, channel=${channel.wire}, status=${status.wire}, attempts=$attempts)"

    private fun whenQueued(
        action: String,
        next: () -> Notification,
    ): Either<TransitionRefused, Notification> =
        if (status == DeliveryStatus.QUEUED) next().right() else TransitionRefused(status, action).left()
}

/** The result of one delivery attempt, appended to the attempt history (data-model §3.6 `DeliveryAttempt`). */
data class DeliveryAttempt(
    val notificationId: NotificationId,
    val number: Int,
    val at: Instant,
    val failure: DeliveryFailure?,
) {
    val succeeded: Boolean get() = failure == null

    companion object {
        /** The attempt that moved [before] to [after] at [at]; null when [after] records no new attempt. */
        fun between(
            before: Notification,
            after: Notification,
            at: Instant,
        ): DeliveryAttempt? =
            if (after.attempts > before.attempts) {
                val failure = if (after.status == DeliveryStatus.SENT) null else after.lastFailure
                DeliveryAttempt(after.id, after.attempts, at, failure)
            } else {
                null
            }
    }
}
