package com.ecommerce.platform.messaging

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

private const val DEFAULT_BATCH_SIZE = 100
private const val DEFAULT_MAX_ATTEMPTS = 5
private const val DEFAULT_MULTIPLIER = 2.0
private val DEFAULT_POLL_INTERVAL: Duration = Duration.ofMillis(500)
private val DEFAULT_SEND_TIMEOUT: Duration = Duration.ofSeconds(10)
private val DEFAULT_RETENTION: Duration = Duration.ofDays(7)
private val DEFAULT_PURGE_INTERVAL: Duration = Duration.ofHours(1)
private val DEFAULT_INITIAL_BACKOFF: Duration = Duration.ofSeconds(1)
private val DEFAULT_MAX_BACKOFF: Duration = Duration.ofSeconds(30)

/**
 * `platform.messaging.*`. Kafka itself is configured through `spring.kafka.*`, whose defaults this library sets
 * (MessagingEnvironmentPostProcessor): `spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`,
 * consumer group `${platform.messaging.consumer.group-id:${spring.application.name}}`, `auto-offset-reset=earliest`.
 */
@ConfigurationProperties("platform.messaging")
data class MessagingProperties(
    /** `false` switches the whole library off (no relay, no listener factory, no consumer support). */
    val enabled: Boolean = true,
    /** The envelope `producer` of this service; defaults to `spring.application.name`. */
    val producer: String? = null,
    val outbox: Outbox = Outbox(),
    val processedEvents: ProcessedEvents = ProcessedEvents(),
    val consumer: Consumer = Consumer(),
    val topics: Topics = Topics(),
) {
    /** `platform.messaging.outbox.*`: the relay and the retention of published rows. */
    data class Outbox(
        /** `false` stores events without relaying them (a service that only writes, or a test). */
        val relayEnabled: Boolean = true,
        /** Wait between two polls when the previous batch was not full. */
        val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
        val batchSize: Int = DEFAULT_BATCH_SIZE,
        /** Longest wait for the Kafka acknowledgment of one record. */
        val sendTimeout: Duration = DEFAULT_SEND_TIMEOUT,
        /** Published rows older than this are purged. */
        val retention: Duration = DEFAULT_RETENTION,
    )

    /** `platform.messaging.processed-events.*`: the idempotency records of consumers. */
    data class ProcessedEvents(
        val retention: Duration = DEFAULT_RETENTION,
        val purgeInterval: Duration = DEFAULT_PURGE_INTERVAL,
    )

    /** `platform.messaging.consumer.*`: listener group and the bounded retry before the dead-letter topic. */
    data class Consumer(
        /** Consumer group and `processed_event.consumer`; defaults to `spring.application.name`. */
        val groupId: String? = null,
        /** Deliveries of a failing record (first delivery included) before it goes to `<topic>.dlt`. */
        val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        val initialInterval: Duration = DEFAULT_INITIAL_BACKOFF,
        val multiplier: Double = DEFAULT_MULTIPLIER,
        val maxInterval: Duration = DEFAULT_MAX_BACKOFF,
    )

    /** `platform.messaging.topics.*`: declaration of the six topics and their dead-letter topics at start-up. */
    data class Topics(
        val create: Boolean = true,
        val replicas: Short = 1,
    )
}
