package com.ecommerce.notification.application

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import com.ecommerce.notification.domain.DeliveryStatus
import com.ecommerce.notification.domain.Notification
import com.ecommerce.notification.domain.NotificationId

/** Why an operator retry was refused. */
sealed interface RetryRefusal {
    /** No notification has the id. */
    data object NotFound : RetryRefusal

    /** Only `failed` notifications can be retried; this one is [status]. */
    data class NotFailed(
        val status: DeliveryStatus,
    ) : RetryRefusal
}

/**
 * `retryFailedNotification` (operator): moves a `failed` notification back to `queued` with a fresh retry budget;
 * the next delivery round sends it. Any other status is refused, so a `sent` message is never sent twice.
 */
class RetryFailed(
    private val notifications: NotificationRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: NotificationId): Either<RetryRefusal, Notification> {
        val current = notifications.findById(id) ?: return RetryRefusal.NotFound.left()
        return current
            .requeue(clock.now())
            .mapLeft { RetryRefusal.NotFailed(current.status) }
            .flatMap { requeued ->
                if (notifications.update(requeued, DeliveryStatus.FAILED)) {
                    requeued.right()
                } else {
                    RetryRefusal.NotFailed(notifications.findById(id)?.status ?: current.status).left()
                }
            }
    }
}

/** `listOwnNotifications` (shopper): the caller's notifications, newest first. */
class ListOwn(
    private val notifications: NotificationRepository,
) {
    suspend operator fun invoke(
        filter: OwnFilter,
        page: PageRequest,
    ): Page<Notification> = notifications.listOwn(filter, page)
}

/** `listFailedNotifications` (operator): failed notifications across accounts, newest failure first. */
class ListFailed(
    private val notifications: NotificationRepository,
) {
    suspend operator fun invoke(
        filter: FailedFilter,
        page: PageRequest,
    ): Page<Notification> = notifications.listFailed(filter, page)
}
