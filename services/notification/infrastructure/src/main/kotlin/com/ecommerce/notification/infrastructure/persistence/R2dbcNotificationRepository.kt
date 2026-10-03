package com.ecommerce.notification.infrastructure.persistence

import com.ecommerce.notification.application.FailedFilter
import com.ecommerce.notification.application.NotificationRepository
import com.ecommerce.notification.application.NotificationRetentionRepository
import com.ecommerce.notification.application.OwnFilter
import com.ecommerce.notification.application.Page
import com.ecommerce.notification.application.PageRequest
import com.ecommerce.notification.domain.AccountId
import com.ecommerce.notification.domain.DedupeKey
import com.ecommerce.notification.domain.DeliveryAttempt
import com.ecommerce.notification.domain.DeliveryFailure
import com.ecommerce.notification.domain.DeliveryStatus
import com.ecommerce.notification.domain.EventId
import com.ecommerce.notification.domain.FailureCategory
import com.ecommerce.notification.domain.MessageContent
import com.ecommerce.notification.domain.Notification
import com.ecommerce.notification.domain.NotificationChannel
import com.ecommerce.notification.domain.NotificationId
import com.ecommerce.notification.domain.NotificationKind
import com.ecommerce.notification.domain.OrderId
import com.ecommerce.notification.domain.RecipientAddress
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Instant
import java.util.UUID

/** [NotificationRepository] on the service's PostgreSQL (`notifications`, `delivery_attempts`) over R2DBC. */
class R2dbcNotificationRepository(
    private val database: DatabaseClient,
) : NotificationRepository {
    override suspend fun keysForEvent(eventId: EventId): Set<DedupeKey> =
        database
            .sql("SELECT kind, channel FROM notifications WHERE source_event_id = :eventId")
            .bind("eventId", eventId.value)
            .map { row ->
                DedupeKey(eventId, kindOf(row.required("kind")), channelOf(row.required("channel")))
            }.all()
            .collectList()
            .awaitSingle()
            .toSet()

    override suspend fun insertIfAbsent(notification: Notification): Boolean =
        database
            .sql(INSERT)
            .bindNotification(notification)
            .bind("sourceEventId", notification.sourceEventId.value)
            .bind("kind", notification.kind.wire)
            .bind("channel", notification.channel.wire)
            .bind("accountId", notification.accountId.value)
            .bindOrNull("orderId", notification.orderId?.value, UUID::class.java)
            .bind("correlationId", notification.correlationId)
            .bind("createdAt", notification.createdAt)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    override suspend fun findById(id: NotificationId): Notification? =
        database.selectWhere("id = :id", mapOf("id" to id.value)).firstOrNull()

    override suspend fun update(
        notification: Notification,
        expected: DeliveryStatus,
    ): Boolean =
        database
            .sql(UPDATE)
            .bindNotification(notification)
            .bind("id", notification.id.value)
            .bind("expected", expected.wire)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    override suspend fun claimDue(
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
    ): List<Notification> =
        database
            .sql(CLAIM)
            .bind("now", now)
            .bind("leaseUntil", leaseUntil)
            .bind("limit", limit)
            .map(::toNotification)
            .all()
            .collectList()
            .awaitSingle()
            .sortedBy { it.createdAt }

    override suspend fun recordAttempt(attempt: DeliveryAttempt) {
        database
            .sql(
                "INSERT INTO delivery_attempts (notification_id, attempt, attempted_at, succeeded, failure_category, " +
                    "failure_reason) VALUES (:id, :attempt, :at, :succeeded, :category, :reason)",
            ).bind("id", attempt.notificationId.value)
            .bind("attempt", attempt.number)
            .bind("at", attempt.at)
            .bind("succeeded", attempt.succeeded)
            .bindOrNull("category", attempt.failure?.category?.name, String::class.java)
            .bindOrNull("reason", attempt.failure?.reason, String::class.java)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    override suspend fun queuedFor(accountId: AccountId): List<Notification> =
        database.selectWhere("account_id = :accountId AND status = 'queued'", mapOf("accountId" to accountId.value))

    override suspend fun forgetRecipient(accountId: AccountId) {
        database
            .sql("UPDATE notifications SET recipient_address = NULL, body = '' WHERE account_id = :accountId")
            .bind("accountId", accountId.value)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    override suspend fun listOwn(
        filter: OwnFilter,
        page: PageRequest,
    ): Page<Notification> {
        val where = Conditions().add("account_id = :accountId", "accountId", filter.accountId.value)
        where.addIf(filter.channel != null, "channel = :channel", "channel", filter.channel?.wire)
        where.addIf(filter.kind != null, "kind = :kind", "kind", filter.kind?.wire)
        return page(where, "created_at DESC, id", page)
    }

    override suspend fun listFailed(
        filter: FailedFilter,
        page: PageRequest,
    ): Page<Notification> {
        val where = Conditions().add("status = 'failed'")
        where.addIf(filter.accountId != null, "account_id = :accountId", "accountId", filter.accountId?.value)
        where.addIf(filter.channel != null, "channel = :channel", "channel", filter.channel?.wire)
        where.addIf(filter.kind != null, "kind = :kind", "kind", filter.kind?.wire)
        where.addIf(filter.failedFrom != null, "failed_at >= :failedFrom", "failedFrom", filter.failedFrom)
        where.addIf(filter.failedTo != null, "failed_at < :failedTo", "failedTo", filter.failedTo)
        return page(where, "failed_at DESC, id", page)
    }

    private suspend fun page(
        where: Conditions,
        order: String,
        page: PageRequest,
    ): Page<Notification> {
        val total =
            where
                .bindTo(database.sql("SELECT count(*) AS total FROM notifications WHERE ${where.sql()}"))
                .map { row -> row.required<Long>("total") }
                .one()
                .awaitSingle()
        val items =
            database.selectWhere(
                "${where.sql()} ORDER BY $order LIMIT :limit OFFSET :offset",
                where.values + mapOf("limit" to page.size, "offset" to page.offset),
            )
        return Page(items, page.page, page.size, total)
    }

    /** SQL conditions joined with AND and their bound values. */
    private class Conditions {
        private val clauses = mutableListOf<String>()
        val values = linkedMapOf<String, Any>()

        fun add(
            clause: String,
            name: String? = null,
            value: Any? = null,
        ): Conditions {
            clauses += clause
            if (name != null && value != null) values[name] = value
            return this
        }

        fun addIf(
            present: Boolean,
            clause: String,
            name: String,
            value: Any?,
        ) {
            if (present) add(clause, name, value)
        }

        fun sql(): String = clauses.joinToString(" AND ")

        fun bindTo(spec: DatabaseClient.GenericExecuteSpec): DatabaseClient.GenericExecuteSpec =
            values.entries.fold(spec) { bound, (name, value) -> bound.bind(name, value) }
    }

    /** SQL and row mapping of the `notifications` table. */
    companion object {
        const val INSERT =
            "INSERT INTO notifications (id, source_event_id, kind, channel, account_id, recipient_address, subject, " +
                "body, order_id, correlation_id, status, attempts, next_attempt_at, last_attempt_at, " +
                "last_error_category, last_error, created_at, sent_at, failed_at, awaiting_recipient) VALUES (:id, " +
                ":sourceEventId, :kind, :channel, :accountId, :recipient, :subject, :body, :orderId, :correlationId, " +
                ":status, :attempts, :nextAttemptAt, :lastAttemptAt, :errorCategory, :error, :createdAt, :sentAt, " +
                ":failedAt, :awaitingRecipient) ON CONFLICT (source_event_id, kind, channel) DO NOTHING"

        const val UPDATE =
            "UPDATE notifications SET recipient_address = :recipient, subject = :subject, body = :body, " +
                "status = :status, attempts = :attempts, next_attempt_at = :nextAttemptAt, " +
                "last_attempt_at = :lastAttemptAt, last_error_category = :errorCategory, last_error = :error, " +
                "sent_at = :sentAt, failed_at = :failedAt, awaiting_recipient = :awaitingRecipient " +
                "WHERE id = :id AND status = :expected"

        // The sub-select locks the due rows and skips rows another instance is claiming right now.
        const val CLAIM =
            "UPDATE notifications SET next_attempt_at = :leaseUntil WHERE id IN (SELECT id FROM notifications " +
                "WHERE status = 'queued' AND next_attempt_at <= :now ORDER BY next_attempt_at, created_at " +
                "LIMIT :limit FOR UPDATE SKIP LOCKED) RETURNING *"

        fun DatabaseClient.GenericExecuteSpec.bindNotification(
            notification: Notification,
        ): DatabaseClient.GenericExecuteSpec =
            bind("id", notification.id.value)
                .bindOrNull("recipient", notification.recipient?.value, String::class.java)
                .bind("subject", notification.content.subject)
                .bind("body", notification.content.body)
                .bind("status", notification.status.wire)
                .bind("attempts", notification.attempts)
                .bindOrNull("nextAttemptAt", notification.nextAttemptAt, Instant::class.java)
                .bindOrNull("lastAttemptAt", notification.lastAttemptAt, Instant::class.java)
                .bindOrNull("errorCategory", notification.lastFailure?.category?.name, String::class.java)
                .bindOrNull("error", notification.lastFailure?.reason, String::class.java)
                .bindOrNull("sentAt", notification.sentAt, Instant::class.java)
                .bindOrNull("failedAt", notification.failedAt, Instant::class.java)
                .bind("awaitingRecipient", notification.awaitingRecipient)

        fun toNotification(row: Readable): Notification =
            Notification(
                id = NotificationId(row.required("id")),
                sourceEventId = EventId(row.required("source_event_id")),
                kind = kindOf(row.required("kind")),
                channel = channelOf(row.required("channel")),
                accountId = AccountId(row.required("account_id")),
                recipient = row.optional<String>("recipient_address")?.let(::RecipientAddress),
                content = MessageContent(row.required("subject"), row.required("body")),
                orderId = row.optional<UUID>("order_id")?.let(::OrderId),
                correlationId = row.required("correlation_id"),
                status = checkNotNull(DeliveryStatus.fromWire(row.required("status"))) { "unknown status" },
                attempts = row.required("attempts"),
                createdAt = row.required("created_at"),
                nextAttemptAt = row.optional("next_attempt_at"),
                lastAttemptAt = row.optional("last_attempt_at"),
                lastFailure = failureOf(row),
                sentAt = row.optional("sent_at"),
                failedAt = row.optional("failed_at"),
                awaitingRecipient = row.required("awaiting_recipient"),
            )

        fun failureOf(row: Readable): DeliveryFailure? {
            val category = row.optional<String>("last_error_category") ?: return null
            return DeliveryFailure(FailureCategory.valueOf(category), row.optional<String>("last_error").orEmpty())
        }

        fun kindOf(wire: String): NotificationKind = checkNotNull(NotificationKind.fromWire(wire)) { "unknown kind" }

        fun channelOf(wire: String): NotificationChannel =
            checkNotNull(NotificationChannel.fromWire(wire)) { "unknown channel" }
    }
}

/** The non-null value of column [name]. */
internal inline fun <reified T : Any> Readable.required(name: String): T =
    checkNotNull(get(name, T::class.java)) { "column $name is null" }

/** The value of column [name], or null. */
internal inline fun <reified T : Any> Readable.optional(name: String): T? = get(name, T::class.java)

/** Binds [value], or a typed NULL when it is null. */
internal fun <T : Any> DatabaseClient.GenericExecuteSpec.bindOrNull(
    name: String,
    value: T?,
    type: Class<T>,
): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)

private suspend fun DatabaseClient.selectWhere(
    condition: String,
    values: Map<String, Any>,
): List<Notification> =
    values.entries
        .fold(sql("SELECT * FROM notifications WHERE $condition")) { spec, (name, value) ->
            spec.bind(name, value)
        }.map { row -> R2dbcNotificationRepository.toNotification(row) }
        .all()
        .collectList()
        .awaitSingle()

/**
 * [NotificationRetentionRepository] on `notifications`: terminal rows only (data-model section 5), oldest first; their
 * `delivery_attempts` rows go with them (`ON DELETE CASCADE`), and rows another purge is deleting are skipped.
 */
class R2dbcNotificationRetention(
    private val database: DatabaseClient,
) : NotificationRetentionRepository {
    override suspend fun deleteTerminalCreatedBefore(
        cutoff: Instant,
        limit: Int,
    ): Int =
        database
            .sql(
                "DELETE FROM notifications WHERE id IN (SELECT id FROM notifications WHERE status <> 'queued' " +
                    "AND created_at < :cutoff ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED)",
            ).bind("cutoff", cutoff)
            .bind("limit", limit)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
            .toInt()
}
