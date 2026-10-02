package com.ecommerce.platform.messaging.it

import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.Topic
import com.ecommerce.platform.messaging.outbox.OutboxPublisher
import com.ecommerce.platform.messaging.testing.EnvelopeFixtures
import com.ecommerce.platform.messaging.testing.OutboxTestSupport
import com.ecommerce.platform.messaging.testing.RecordedEvents
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Duration
import java.util.UUID

private val QUIET_WINDOW: Duration = Duration.ofMillis(500)

@MessagingIntegrationTest
class OutboxIntegrationTest(
    @Autowired private val outbox: OutboxPublisher,
    @Autowired private val recorded: RecordedEvents,
    @Autowired private val meters: MeterRegistry,
    @Autowired database: DatabaseClient,
    @Autowired transactionManager: ReactiveTransactionManager,
) {
    private val transactions = TransactionalOperator.create(transactionManager)
    private val rows = OutboxTestSupport(database)

    @Test
    fun `an event published in a transaction reaches its topic keyed by aggregate, with headers and JSON`() {
        val orderId = UUID.randomUUID()
        val envelope = EnvelopeFixtures.orderPlaced(orderId = orderId)

        runBlocking { transactions.executeAndAwait { outbox.publish(envelope) } }

        val record = recorded.awaitEvent(envelope.eventId)
        record.topic shouldBe Topic.ORDER
        record.key shouldBe orderId.toString()
        record.headers["eventId"] shouldBe envelope.eventId.toString()
        record.headers["type"] shouldBe "OrderPlaced"
        record.headers["correlationId"] shouldBe EnvelopeFixtures.CORRELATION_ID
        EnvelopeJson.mapper.readTree(record.value) shouldBe EnvelopeJson.mapper.readTree(EnvelopeJson.write(envelope))
        record.value shouldContain "\"occurredAt\":\"2026-10-02T10:00:00Z\""

        rows.awaitPublished(envelope.eventId)
        val row = rows.row(envelope.eventId).shouldNotBeNull()
        row.attempts shouldBe 1
        row.lastError.shouldBeNull()
        meters.counter("outbox.published").count() shouldBeGreaterThan 0.0
    }

    @Test
    fun `events of one aggregate published together arrive in the order they were written`() {
        val orderId = UUID.randomUUID()
        val events = List(BURST) { EnvelopeFixtures.orderPlaced(orderId = orderId) }

        runBlocking { transactions.executeAndAwait { outbox.publishAll(events) } }

        recorded.awaitEvent(events.last().eventId)
        recorded.on(Topic.ORDER).filter { it.key == orderId.toString() }.map { it.envelope.eventId } shouldBe
            events.map { it.eventId }
    }

    @Test
    fun `a rolled-back transaction publishes nothing`() {
        val orderId = UUID.randomUUID()
        val rolledBack = EnvelopeFixtures.orderPlaced(orderId = orderId)
        val committed = EnvelopeFixtures.orderPlaced(orderId = orderId)

        shouldThrow<IllegalStateException> {
            runBlocking {
                transactions.executeAndAwait {
                    outbox.publish(rolledBack)
                    error("the aggregate change failed")
                }
            }
        }
        runBlocking { transactions.executeAndAwait { outbox.publish(committed) } }

        // Same key, same partition: once the committed event has arrived, the rolled-back one can no longer come.
        recorded.awaitEvent(committed.eventId)
        rows.row(rolledBack.eventId).shouldBeNull()
        recorded.all.none { it.headers["eventId"] == rolledBack.eventId.toString() } shouldBe true
        recorded.expectNone(QUIET_WINDOW) { it.headers["eventId"] == rolledBack.eventId.toString() }
    }

    @Test
    fun `publishing outside a transaction is refused`() {
        val envelope = EnvelopeFixtures.orderPlaced(orderId = UUID.randomUUID())

        shouldThrow<IllegalStateException> { runBlocking { outbox.publish(envelope) } }.message shouldContain
            "inside the transaction"
        rows.row(envelope.eventId).shouldBeNull()
    }

    @Test
    fun `an event type outside the registry cannot be stored`() {
        val unknown = EnvelopeFixtures.orderPlaced().copy(type = "OrderTeleported")

        shouldThrow<IllegalArgumentException> {
            runBlocking { transactions.executeAndAwait { outbox.publish(unknown) } }
        }
        rows.row(unknown.eventId).shouldBeNull()
    }

    private companion object {
        const val BURST = 5
    }
}
