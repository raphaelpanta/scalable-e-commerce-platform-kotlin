package com.ecommerce.platform.messaging.it

import com.ecommerce.platform.messaging.outbox.OutboxHeaders
import com.ecommerce.platform.messaging.outbox.OutboxPublisher
import com.ecommerce.platform.messaging.testing.EnvelopeFixtures
import com.ecommerce.platform.messaging.testing.RecordedEvents
import com.ecommerce.platform.observability.Traceparents
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Duration
import java.util.UUID

/**
 * T188 (FR-025, research §3): an event written to the outbox inside a span carries that span's W3C `traceparent`
 * through the relay as a Kafka record header, and the consumer's handler runs in the same trace (its MDC `traceId`
 * is the producer's), although the relay sends the record later, outside the producer's span.
 */
@MessagingIntegrationTest
class TraceparentIntegrationTest(
    @Autowired private val outbox: OutboxPublisher,
    @Autowired private val recorded: RecordedEvents,
    @Autowired private val listeners: TestListeners,
    @Autowired private val observations: ObservationRegistry,
    @Autowired transactionManager: ReactiveTransactionManager,
) {
    private val transactions = TransactionalOperator.create(transactionManager)

    @Test
    fun `the producer's traceparent round-trips through the outbox and the consumer continues its trace`() {
        val envelope = EnvelopeFixtures.paymentApproved(paymentId = UUID.randomUUID())
        val producer = Observation.start("t188.producer", observations)
        val producerTraceparent = producer.openScope().use { Traceparents.ofCurrentThread() }.shouldNotBeNull()
        val producerTrace = producerTraceparent.split("-")[1]

        // Like a request handler: the observation travels in the Reactor context of the coroutine, not the thread.
        runBlocking {
            mono { transactions.executeAndAwait { outbox.publish(envelope) } }
                .contextWrite { context -> context.put(ObservationThreadLocalAccessor.KEY, producer) }
                .awaitSingle()
        }
        producer.stop()

        val record = recorded.awaitEvent(envelope.eventId)
        record.headers[OutboxHeaders.TRACEPARENT] shouldBe producerTraceparent
        record.headers[OutboxHeaders.CORRELATION_ID] shouldBe envelope.correlationId
        await().atMost(DELIVERY_TIMEOUT).until { listeners.handlerTraceIds.containsKey(envelope.eventId) }
        listeners.handlerTraceIds[envelope.eventId] shouldBe producerTrace
        listeners.handlerCorrelationIds[envelope.eventId] shouldBe envelope.correlationId
    }

    @Test
    fun `an event written outside any span carries no traceparent`() {
        val envelope = EnvelopeFixtures.paymentApproved(paymentId = UUID.randomUUID())

        runBlocking { transactions.executeAndAwait { outbox.publish(envelope) } }

        val record = recorded.awaitEvent(envelope.eventId)
        record.headers[OutboxHeaders.TRACEPARENT].shouldBeNull()
        record.headers[OutboxHeaders.EVENT_ID] shouldBe envelope.eventId.toString()
    }

    private companion object {
        val DELIVERY_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
