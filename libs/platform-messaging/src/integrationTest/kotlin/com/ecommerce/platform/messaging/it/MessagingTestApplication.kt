package com.ecommerce.platform.messaging.it

import com.ecommerce.platform.correlation.CorrelationIds
import com.ecommerce.platform.messaging.consumer.EventListenerSupport
import com.ecommerce.platform.messaging.consumer.Handled
import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.Topic
import com.ecommerce.platform.messaging.testing.KafkaTestConfig
import com.ecommerce.platform.messaging.testing.RecordedEventsConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.MDC
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A minimal service: the messaging auto-configuration, Flyway `classpath:db/messaging` and two listeners. */
@SpringBootApplication
class MessagingTestApplication

/** Same image as the services' integration tests (`Images.POSTGRES` of platform-core's test fixtures). */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfig {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer("postgres:18-alpine")
}

/** One application context (PostgreSQL, Kafka, recorded events) shared by every integration test class. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(classes = [MessagingTestApplication::class])
@Import(PostgresTestConfig::class, KafkaTestConfig::class, RecordedEventsConfig::class)
annotation class MessagingIntegrationTest

/**
 * Listeners written the way a service writes them. Payment events are handled idempotently and recorded;
 * notification events always fail, so they end up in the dead-letter topic after the configured attempts.
 */
@Component
class TestListeners(
    private val events: EventListenerSupport,
) {
    val effects = CopyOnWriteArrayList<UUID>()

    /** The MDC `correlationId` seen by the payment handler, after the real `processed_event` insert (T145). */
    val handlerCorrelationIds = ConcurrentHashMap<UUID, String>()
    val outcomes = ConcurrentHashMap<UUID, MutableList<Handled<Unit>>>()
    val failingAttempts = ConcurrentHashMap<UUID, AtomicInteger>()

    @KafkaListener(topics = [Topic.PAYMENT])
    fun onPayment(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        val handled =
            events.dispatch(record, ack) { envelope ->
                MDC.get(CorrelationIds.MDC_KEY)?.let { handlerCorrelationIds[envelope.eventId] = it }
                effects += envelope.eventId
            }
        val eventId = EnvelopeJson.read(record.value()).eventId
        outcomes.computeIfAbsent(eventId) { CopyOnWriteArrayList() } += handled
    }

    @KafkaListener(topics = [Topic.NOTIFICATION])
    fun onNotification(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        events.dispatch(record, ack) { envelope ->
            failingAttempts.computeIfAbsent(envelope.eventId) { AtomicInteger() }.incrementAndGet()
            error("notification handler always fails")
        }
    }
}
