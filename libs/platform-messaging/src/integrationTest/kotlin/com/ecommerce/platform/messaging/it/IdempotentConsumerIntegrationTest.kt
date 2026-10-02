package com.ecommerce.platform.messaging.it

import com.ecommerce.platform.messaging.consumer.Handled
import com.ecommerce.platform.messaging.consumer.IdempotentConsumer
import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.Topic
import com.ecommerce.platform.messaging.testing.EnvelopeFixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

private val DELIVERY_TIMEOUT: Duration = Duration.ofSeconds(30)

@MessagingIntegrationTest
class IdempotentConsumerIntegrationTest(
    @Autowired private val idempotent: IdempotentConsumer,
    @Autowired private val listeners: TestListeners,
    @Autowired private val kafka: KafkaTemplate<String, String>,
    @Autowired private val database: DatabaseClient,
) {
    @Test
    fun `the same eventId delivered twice runs the handler once and reports the second delivery as Duplicate`() {
        val envelope = EnvelopeFixtures.paymentApproved(paymentId = UUID.randomUUID())
        val json = EnvelopeJson.write(envelope)

        repeat(
            2,
        ) { kafka.send(Topic.PAYMENT, envelope.aggregateId.toString(), json).get(SEND_TIMEOUT, TimeUnit.SECONDS) }

        await().atMost(DELIVERY_TIMEOUT).until { listeners.outcomes[envelope.eventId]?.size == 2 }
        listeners.outcomes.getValue(envelope.eventId) shouldContainExactly
            listOf(Handled.Processed(Unit), Handled.Duplicate)
        listeners.effects.count { it == envelope.eventId } shouldBe 1
        processedMarkers(envelope.eventId) shouldBe 1L
    }

    @Test
    fun `a handler that fails leaves no marker so the redelivery runs it again`() {
        val envelope = EnvelopeFixtures.received(EnvelopeFixtures.paymentApproved(paymentId = UUID.randomUUID()))

        shouldThrow<IllegalStateException> {
            runBlocking { idempotent.handle(envelope, "it-consumer") { error("database effect failed") } }
        }
        processedMarkers(envelope.eventId) shouldBe 0L

        runBlocking { idempotent.handle(envelope, "it-consumer") { "done" } } shouldBe Handled.Processed("done")
        runBlocking { idempotent.handle(envelope, "it-consumer") { "again" } } shouldBe Handled.Duplicate
    }

    @Test
    fun `deduplication is per consumer, so two services each handle the same event once`() {
        val envelope = EnvelopeFixtures.orderPlaced(orderId = UUID.randomUUID())

        runBlocking { idempotent.handle(envelope, "payment") { 1 } } shouldBe Handled.Processed(1)
        runBlocking { idempotent.handle(envelope, "notification") { 2 } } shouldBe Handled.Processed(2)
        runBlocking { idempotent.handle(envelope, "payment") { 0 } } shouldBe Handled.Duplicate
    }

    private fun processedMarkers(eventId: UUID): Long =
        runBlocking {
            database
                .sql("SELECT count(*) AS markers FROM processed_event WHERE event_id = :id")
                .bind("id", eventId)
                .map { row, _ -> row.get("markers", Long::class.javaObjectType) ?: 0L }
                .one()
                .awaitSingle()
        }

    private companion object {
        const val SEND_TIMEOUT = 10L
    }
}
