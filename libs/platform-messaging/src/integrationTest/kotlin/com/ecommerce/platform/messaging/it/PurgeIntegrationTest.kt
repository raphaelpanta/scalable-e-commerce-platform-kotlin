package com.ecommerce.platform.messaging.it

import com.ecommerce.platform.messaging.consumer.ProcessedEventPurge
import com.ecommerce.platform.messaging.envelope.Topic
import com.ecommerce.platform.messaging.outbox.OutboxPurge
import com.ecommerce.platform.messaging.testing.OutboxTestSupport
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val EIGHT_DAYS: Duration = Duration.ofDays(8)
private val ONE_DAY: Duration = Duration.ofDays(1)

@MessagingIntegrationTest
class PurgeIntegrationTest(
    @Autowired private val processedEvents: ProcessedEventPurge,
    @Autowired private val outboxPurge: OutboxPurge,
    @Autowired private val database: DatabaseClient,
) {
    private val outbox = OutboxTestSupport(database)

    @Test
    fun `processed-event markers older than the 7-day retention are purged and recent ones kept`() {
        val expired = UUID.randomUUID()
        val recent = UUID.randomUUID()
        runBlocking {
            insertMarker(expired, Instant.now().minus(EIGHT_DAYS))
            insertMarker(recent, Instant.now().minus(ONE_DAY))
        }

        runBlocking { processedEvents.purge() } shouldBeGreaterThanOrEqual 1L

        runBlocking { markerExists(expired) } shouldBe false
        runBlocking { markerExists(recent) } shouldBe true
    }

    @Test
    fun `outbox rows published more than 7 days ago are purged and recent ones kept`() {
        val expired = UUID.randomUUID()
        val recent = UUID.randomUUID()
        runBlocking {
            insertPublishedRow(expired, Instant.now().minus(EIGHT_DAYS))
            insertPublishedRow(recent, Instant.now().minus(ONE_DAY))
        }

        runBlocking { outboxPurge.purge() } shouldBeGreaterThanOrEqual 1L

        outbox.row(expired).shouldBeNull()
        outbox.row(recent).shouldNotBeNull()
    }

    private suspend fun insertMarker(
        eventId: UUID,
        processedAt: Instant,
    ) {
        database
            .sql("INSERT INTO processed_event (event_id, consumer, processed_at) VALUES (:id, 'purge-test', :at)")
            .bind("id", eventId)
            .bind("at", processedAt)
            .await()
    }

    private suspend fun markerExists(eventId: UUID): Boolean =
        database
            .sql("SELECT count(*) AS markers FROM processed_event WHERE event_id = :id")
            .bind("id", eventId)
            .map { row, _ -> row.get("markers", Long::class.javaObjectType) == 1L }
            .one()
            .awaitSingle()

    private suspend fun insertPublishedRow(
        id: UUID,
        publishedAt: Instant,
    ) {
        database
            .sql(
                "INSERT INTO outbox (id, aggregate_id, topic, event_type, event_key, payload, headers, occurred_at, " +
                    "published_at, attempts) VALUES (:id, :key, :topic, 'OrderPlaced', :key, '{}', '{}', :at, :at, 1)",
            ).bind("id", id)
            .bind("key", id.toString())
            .bind("topic", Topic.ORDER)
            .bind("at", publishedAt)
            .await()
    }
}
