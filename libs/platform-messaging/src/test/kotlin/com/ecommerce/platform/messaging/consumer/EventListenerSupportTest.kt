package com.ecommerce.platform.messaging.consumer

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ecommerce.platform.correlation.CorrelationIds
import com.ecommerce.platform.messaging.envelope.Envelope
import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.envelope.ReceivedEnvelope
import com.ecommerce.platform.messaging.envelope.Topic
import com.ecommerce.platform.messaging.testing.EnvelopeFixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.kafka.support.Acknowledgment
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * T145 (FR-025, SC-007): [EventListenerSupport.dispatch] binds the envelope's `correlationId` around the handler, so
 * the MDC (hence every consumer-side log line) and [CorrelationIds.current] carry it, also after the handler's
 * coroutine resumed on other threads; nothing leaks onto the consumer thread afterwards.
 */
class EventListenerSupportTest :
    FunSpec({
        val log = LoggerFactory.getLogger(EventListenerSupportTest::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        val idempotent = mockk<IdempotentConsumer>()
        val events = EventListenerSupport(idempotent, "test")

        beforeSpec {
            appender.start()
            log.addAppender(appender)
            // Like the real consumer: the block runs after the processed_event insert, a Reactor signal on another
            // thread.
            coEvery { idempotent.handle(any<ReceivedEnvelope>(), any(), any<suspend (ReceivedEnvelope) -> Any?>()) }
                .coAnswers {
                    Mono.delay(Duration.ofMillis(1)).awaitSingle()
                    Handled.Processed(thirdArg<suspend (ReceivedEnvelope) -> Any?>().invoke(firstArg()))
                }
        }
        afterSpec { log.detachAppender(appender) }
        beforeTest { appender.list.clear() }

        fun dispatch(envelope: Envelope<*>): Handled<Seen> {
            val ack = mockk<Acknowledgment>(relaxed = true)
            val record =
                ConsumerRecord(Topic.PAYMENT, 0, 7L, envelope.aggregateId.toString(), EnvelopeJson.write(envelope))
            return events
                .dispatch(record, ack) {
                    val afterHop = seen().also { log.info("handled after the insert") }
                    val elsewhere = onAnotherDispatcher { seen().also { log.info("handled on another dispatcher") } }
                    Seen(afterHop, elsewhere)
                }.also { verify(exactly = 1) { ack.acknowledge() } }
        }

        test("the envelope correlation id is in the MDC, the log lines and the coroutine context of the handler") {
            val envelope =
                EnvelopeFixtures.envelope(
                    EventType.PaymentApproved,
                    mapOf("x" to 1),
                    correlationId = "corr-42",
                )

            val handled = dispatch(envelope)

            handled shouldBe Handled.Processed(Seen(View("corr-42", "corr-42"), View("corr-42", "corr-42")))
            appender.list shouldHaveSize 2
            appender.list.map { it.mdcPropertyMap[CorrelationIds.MDC_KEY] } shouldBe listOf("corr-42", "corr-42")
            MDC.get(CorrelationIds.MDC_KEY) shouldBe null
        }

        test("a malformed correlation id is replaced by a UUID, the same one throughout the handler") {
            val envelope =
                EnvelopeFixtures.envelope(EventType.PaymentApproved, mapOf("x" to 1), correlationId = "bad id; drop")

            val outcome = (dispatch(envelope) as Handled.Processed).value

            val id = outcome.afterHop.mdc
            id shouldNotBe null
            id.orEmpty() shouldMatch "[0-9a-f-]{36}"
            outcome shouldBe Seen(View(id, id), View(id, id))
            appender.list.map { it.mdcPropertyMap[CorrelationIds.MDC_KEY] } shouldBe listOf(id, id)
        }
    }) {
    data class View(
        val mdc: String?,
        val current: String?,
    )

    data class Seen(
        val afterHop: View,
        val elsewhere: View,
    )

    private companion object {
        suspend fun seen(): View = View(MDC.get(CorrelationIds.MDC_KEY), CorrelationIds.current())

        suspend fun <T> onAnotherDispatcher(
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
            block: suspend () -> T,
        ): T = withContext(dispatcher) { block() }
    }
}
