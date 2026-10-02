package com.ecommerce.notification.domain

import java.util.UUID

/** Identifier of a [Notification] (data-model §2: each concept has its own typed id). */
@JvmInline
value class NotificationId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()

    companion object {
        /** A new, random id. */
        fun random(): NotificationId = NotificationId(UUID.randomUUID())
    }
}

/** Identifier of the recipient account (owned by identity). */
@JvmInline
value class AccountId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}

/** `eventId` of the consumed event that triggered a notification: the deduplication key (FR-019). */
@JvmInline
value class EventId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}

/** Identifier of the related order (owned by order). */
@JvmInline
value class OrderId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}
