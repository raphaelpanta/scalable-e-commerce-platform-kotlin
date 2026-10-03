package com.ecommerce.notification.application

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import com.ecommerce.notification.domain.AccountId
import com.ecommerce.notification.domain.DeliveryStatus
import com.ecommerce.notification.domain.Notification
import com.ecommerce.notification.domain.NotificationId

/** The roles of an authenticated caller that the notification use cases distinguish. */
enum class CallerRole {
    SHOPPER,
    OPERATOR,
}

/**
 * Who calls a use case: the authenticated account and its roles. The operator use cases authorise it themselves
 * (Constitution III: authorisation in the application layer, not only at the edge).
 */
data class Caller(
    val accountId: AccountId,
    val roles: Set<CallerRole>,
) {
    val isOperator: Boolean get() = CallerRole.OPERATOR in roles
}

/** Why an operator retry was refused. */
sealed interface RetryRefusal {
    /** No notification has the id. */
    data object NotFound : RetryRefusal

    /** Only `failed` notifications can be retried; this one is [status]. */
    data class NotFailed(
        val status: DeliveryStatus,
    ) : RetryRefusal
}

/** The caller does not hold the operator role: an operator use case refuses it before reading anything (403). */
data object Forbidden : RetryRefusal

/** [caller] when it is an operator, [Forbidden] otherwise (deny by default). */
internal fun operatorOnly(caller: Caller): Either<Forbidden, Caller> =
    if (caller.isOperator) caller.right() else Forbidden.left()

/**
 * `retryFailedNotification` (operator): moves a `failed` notification back to `queued` with a fresh retry budget;
 * the next delivery round sends it. Any other status is refused, so a `sent` message is never sent twice. A caller
 * that is not an operator is [Forbidden] and nothing is read.
 */
class RetryFailed(
    private val notifications: NotificationRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        caller: Caller,
        id: NotificationId,
    ): Either<RetryRefusal, Notification> =
        either {
            operatorOnly(caller).bind()
            val current = notifications.findById(id) ?: raise(RetryRefusal.NotFound)
            val requeued = current.requeue(clock.now()).mapLeft { RetryRefusal.NotFailed(current.status) }.bind()
            ensure(notifications.update(requeued, DeliveryStatus.FAILED)) {
                RetryRefusal.NotFailed(notifications.findById(id)?.status ?: current.status)
            }
            requeued
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

/**
 * `listFailedNotifications` (operator): failed notifications across accounts, newest failure first. A caller that is
 * not an operator is [Forbidden] and nothing is read.
 */
class ListFailed(
    private val notifications: NotificationRepository,
) {
    suspend operator fun invoke(
        caller: Caller,
        filter: FailedFilter,
        page: PageRequest,
    ): Either<Forbidden, Page<Notification>> = operatorOnly(caller).map { notifications.listFailed(filter, page) }
}
