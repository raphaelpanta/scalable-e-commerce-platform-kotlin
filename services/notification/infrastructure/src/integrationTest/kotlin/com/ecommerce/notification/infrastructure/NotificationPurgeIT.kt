package com.ecommerce.notification.infrastructure

import com.ecommerce.notification.infrastructure.jobs.NotificationPurgeJob
import com.ecommerce.platform.messaging.testing.RecordedEventsConfig
import com.ecommerce.platform.testing.KafkaTestConfig
import com.ecommerce.platform.testing.PostgresTestConfig
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)
private const val EXPIRED_DAYS = 91L
private const val RECENT_DAYS = 89L
private const val RETENTION_DAYS = 90L
private const val EXPIRED_TERMINAL_ROWS = 3

/**
 * T140 (data-model section 5, FR-007): the retention purge deletes sent, failed and suppressed notifications older
 * than `notification.retention.notifications` (90 days) with their `delivery_attempts`, and keeps queued and recent
 * ones. Same context as [DeliveryIT].
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresTestConfig::class, KafkaTestConfig::class, RecordedEventsConfig::class, DeliveryTestConfig::class)
class NotificationPurgeIT(
    @Autowired private val purgeJob: NotificationPurgeJob,
    @Autowired private val properties: NotificationProperties,
    @Autowired private val database: DatabaseClient,
) {
    @Test
    fun `terminal notifications past the retention are deleted with their attempts, queued and recent ones stay`() {
        val sent = insert("sent", EXPIRED_DAYS)
        val failed = insert("failed", EXPIRED_DAYS)
        val suppressed = insert("suppressed", EXPIRED_DAYS)
        val queued = insert("queued", EXPIRED_DAYS)
        val recent = insert("sent", RECENT_DAYS)

        val purged = mono { purgeJob.purgeNow() }.block(QUERY_TIMEOUT) ?: 0

        purged shouldBeGreaterThanOrEqual EXPIRED_TERMINAL_ROWS
        listOf(sent, failed, suppressed).forEach { id ->
            count("notifications", "id", id) shouldBe 0L
            count("delivery_attempts", "notification_id", id) shouldBe 0L
        }
        listOf(queued, recent).forEach { id ->
            count("notifications", "id", id) shouldBe 1L
            count("delivery_attempts", "notification_id", id) shouldBe 1L
        }
        properties.retention.notifications shouldBe Duration.ofDays(RETENTION_DAYS)
        purgeJob.isRunning shouldBe true
    }

    /** A notification in [status] created [ageDays] ago, never due again, with one attempt row. */
    private fun insert(
        status: String,
        ageDays: Long,
    ): UUID {
        val id = UUID.randomUUID()
        val createdAt = Instant.now().minus(Duration.ofDays(ageDays))
        database
            .sql(
                "INSERT INTO notifications (id, source_event_id, kind, channel, account_id, recipient_address, " +
                    "subject, body, correlation_id, status, attempts, created_at) VALUES (:id, :eventId, " +
                    "'order_shipped', 'email', :accountId, NULL, 'Order shipped', '', 'purge-test', :status, 1, :at)",
            ).bind("id", id)
            .bind("eventId", UUID.randomUUID())
            .bind("accountId", UUID.randomUUID())
            .bind("status", status)
            .bind("at", createdAt)
            .fetch()
            .rowsUpdated()
            .block(QUERY_TIMEOUT)
        database
            .sql(
                "INSERT INTO delivery_attempts (notification_id, attempt, attempted_at, succeeded) " +
                    "VALUES (:id, 1, :at, true)",
            ).bind("id", id)
            .bind("at", createdAt)
            .fetch()
            .rowsUpdated()
            .block(QUERY_TIMEOUT)
        return id
    }

    private fun count(
        table: String,
        column: String,
        id: UUID,
    ): Long =
        database
            .sql("SELECT count(*) AS n FROM $table WHERE $column = :id")
            .bind("id", id)
            .map { row -> row.get("n", Long::class.javaObjectType) ?: 0L }
            .one()
            .block(QUERY_TIMEOUT) ?: 0L
}
