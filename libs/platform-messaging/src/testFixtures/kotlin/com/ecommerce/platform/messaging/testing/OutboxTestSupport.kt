package com.ecommerce.platform.messaging.testing

import org.awaitility.Awaitility.await
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Default wait of the outbox assertions. */
val OUTBOX_TIMEOUT: Duration = Duration.ofSeconds(30)

private val QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)

/** The state of one outbox row. */
data class OutboxRow(
    val id: UUID,
    val topic: String,
    val eventType: String,
    val publishedAt: Instant?,
    val attempts: Int,
    val lastError: String?,
)

/**
 * Assertions on the service's `outbox` table for tests (blocking on purpose, test code only):
 * `OutboxTestSupport(databaseClient).awaitPublished(envelope.eventId)`.
 */
class OutboxTestSupport(
    private val database: DatabaseClient,
) {
    /** The row of [eventId], or null when no such event was stored (for example after a rollback). */
    fun row(eventId: UUID): OutboxRow? =
        database
            .sql(
                "SELECT id, topic, event_type, published_at, attempts, last_error FROM outbox WHERE id = :id",
            ).bind("id", eventId)
            .map { row, _ ->
                OutboxRow(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    topic = checkNotNull(row.get("topic", String::class.java)),
                    eventType = checkNotNull(row.get("event_type", String::class.java)),
                    publishedAt = row.get("published_at", Instant::class.java),
                    attempts = checkNotNull(row.get("attempts", Int::class.javaObjectType)),
                    lastError = row.get("last_error", String::class.java),
                )
            }.one()
            .block(QUERY_TIMEOUT)

    /** The number of rows the relay has not published yet. */
    fun pending(): Long =
        checkNotNull(
            database
                .sql("SELECT count(*) AS pending FROM outbox WHERE published_at IS NULL")
                .map { row, _ -> checkNotNull(row.get("pending", Long::class.javaObjectType)) }
                .one()
                .block(QUERY_TIMEOUT),
        )

    /** Waits until the relay has published [eventId] and returns the publication time. */
    fun awaitPublished(
        eventId: UUID,
        timeout: Duration = OUTBOX_TIMEOUT,
    ): Instant {
        var publishedAt: Instant? = null
        await().atMost(timeout).until {
            publishedAt = row(eventId)?.publishedAt
            publishedAt != null
        }
        return checkNotNull(publishedAt)
    }

    /** Waits until every stored event has been published. */
    fun awaitDrained(timeout: Duration = OUTBOX_TIMEOUT) {
        await().atMost(timeout).until { pending() == 0L }
    }
}
