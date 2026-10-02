package com.ecommerce.platform.messaging.it

import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.envelope.Topic
import com.ecommerce.platform.messaging.outbox.OutboxPublisher
import com.ecommerce.platform.messaging.testing.EnvelopeFixtures
import com.ecommerce.platform.messaging.testing.RecordedEvents
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.util.UUID
import java.util.concurrent.TimeUnit

private val NOTIFICATION_DLT = Topic.deadLetter(Topic.NOTIFICATION)

@MessagingIntegrationTest
class DeadLetterIntegrationTest(
    @Autowired private val outbox: OutboxPublisher,
    @Autowired private val recorded: RecordedEvents,
    @Autowired private val listeners: TestListeners,
    @Autowired private val kafka: KafkaTemplate<String, String>,
    @Autowired transactionManager: ReactiveTransactionManager,
) {
    private val transactions = TransactionalOperator.create(transactionManager)

    @Test
    fun `a handler that keeps failing gets the configured attempts and its record goes to the dead-letter topic`() {
        val notificationId = UUID.randomUUID()
        val envelope =
            EnvelopeFixtures.envelope(
                EventType.NotificationSent,
                mapOf("notificationId" to notificationId.toString(), "channel" to "email"),
                aggregateId = notificationId,
            )

        runBlocking { transactions.executeAndAwait { outbox.publish(envelope) } }

        val dead = recorded.awaitRecord { it.topic == NOTIFICATION_DLT && it.key == notificationId.toString() }
        dead.envelope.eventId shouldBe envelope.eventId
        dead.headers["eventId"] shouldBe envelope.eventId.toString()
        dead.headers["kafka_dlt-original-topic"] shouldBe Topic.NOTIFICATION
        listeners.failingAttempts.getValue(envelope.eventId).get() shouldBe CONFIGURED_ATTEMPTS
    }

    @Test
    fun `a record that is not an envelope goes to the dead-letter topic without retries`() {
        val key = "malformed-${UUID.randomUUID()}"

        kafka.send(Topic.NOTIFICATION, key, "{\"not\":\"an envelope\"}").get(SEND_TIMEOUT, TimeUnit.SECONDS)

        val dead = recorded.awaitRecord { it.topic == NOTIFICATION_DLT && it.key == key }
        dead.value shouldBe "{\"not\":\"an envelope\"}"
        dead.headers.values.any { it.contains("MalformedEnvelopeException") } shouldBe true
    }

    private companion object {
        /** platform.messaging.consumer.max-attempts of the test configuration. */
        const val CONFIGURED_ATTEMPTS = 3
        const val SEND_TIMEOUT = 10L
    }
}
