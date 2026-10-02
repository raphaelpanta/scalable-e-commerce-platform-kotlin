package com.ecommerce.platform.messaging.testing

import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.envelope.ReceivedEnvelope
import com.ecommerce.platform.messaging.envelope.Topic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.awaitility.Awaitility.await
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListener
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern

/** Default wait of the `await*` assertions. */
val RECORDED_EVENTS_TIMEOUT: Duration = Duration.ofSeconds(30)

private const val METADATA_REFRESH_MS = 1000

/** One record seen by [RecordedEvents]: where it was published, its key, headers (UTF-8) and value. */
data class RecordedEvent(
    val topic: String,
    val partition: Int,
    val key: String?,
    val headers: Map<String, String>,
    val value: String,
) {
    /** The value parsed as an envelope (dead-letter records carry the original envelope as well). */
    val envelope: ReceivedEnvelope by lazy { EnvelopeJson.read(value) }

    internal companion object {
        fun of(record: ConsumerRecord<String, String>): RecordedEvent =
            RecordedEvent(
                topic = record.topic(),
                partition = record.partition(),
                key = record.key(),
                headers = record.headers().associate { it.key() to it.value()?.toString(Charsets.UTF_8).orEmpty() },
                value = record.value().orEmpty(),
            )
    }
}

/**
 * A test consumer (its own random group, reading from the earliest offset) that records every record of
 * [topics], so that service tests can assert what was published: `recorded.awaitType(EventType.OrderPlaced)`.
 * It is a [SmartLifecycle] bean when declared through [RecordedEventsConfig], or started and stopped by hand.
 */
class RecordedEvents(
    bootstrapServers: List<String>,
    topics: Collection<String>,
) : SmartLifecycle {
    private val records = CopyOnWriteArrayList<RecordedEvent>()
    private val container: KafkaMessageListenerContainer<String, String>

    init {
        require(topics.isNotEmpty()) { "at least one topic to record" }
        val consumerProperties =
            mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers.joinToString(","),
                ConsumerConfig.GROUP_ID_CONFIG to "recorded-events-${UUID.randomUUID()}",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.METADATA_MAX_AGE_CONFIG to METADATA_REFRESH_MS,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            )
        // A pattern subscription also picks up topics (such as dead-letter topics) created after the start.
        val subscription = Pattern.compile(topics.joinToString("|") { Pattern.quote(it) })
        val properties =
            ContainerProperties(subscription).apply {
                setMessageListener(MessageListener<String, String> { records.add(RecordedEvent.of(it)) })
            }
        container = KafkaMessageListenerContainer(DefaultKafkaConsumerFactory(consumerProperties), properties)
    }

    /** Every record seen so far, in arrival order. */
    val all: List<RecordedEvent> get() = records.toList()

    /** The records seen on [topic]. */
    fun on(topic: String): List<RecordedEvent> = records.filter { it.topic == topic }

    /** The envelopes of type [type] seen so far. */
    fun ofType(type: EventType): List<ReceivedEnvelope> =
        records.filter { it.topic == type.topic && it.headers["type"] == type.name }.map { it.envelope }

    /** Waits until a record matching [predicate] arrives and returns it. */
    fun awaitRecord(
        timeout: Duration = RECORDED_EVENTS_TIMEOUT,
        predicate: (RecordedEvent) -> Boolean,
    ): RecordedEvent {
        var found: RecordedEvent? = null
        await().atMost(timeout).until {
            found = records.firstOrNull(predicate)
            found != null
        }
        return checkNotNull(found)
    }

    /** Waits for the envelope with [eventId] on its regular (not dead-letter) topic. */
    fun awaitEvent(
        eventId: UUID,
        timeout: Duration = RECORDED_EVENTS_TIMEOUT,
    ): RecordedEvent = awaitRecord(timeout) { Topic.isValid(it.topic) && it.headers["eventId"] == eventId.toString() }

    /** Waits for an envelope of [type] matching [predicate] and returns it. */
    fun awaitType(
        type: EventType,
        timeout: Duration = RECORDED_EVENTS_TIMEOUT,
        predicate: (ReceivedEnvelope) -> Boolean = { true },
    ): ReceivedEnvelope =
        awaitRecord(timeout) {
            it.topic == type.topic && it.headers["type"] == type.name && predicate(it.envelope)
        }.envelope

    /** Asserts that no record matching [predicate] arrives during [window]. */
    fun expectNone(
        window: Duration,
        predicate: (RecordedEvent) -> Boolean,
    ) {
        await().during(window).atMost(window.plusSeconds(1)).until { records.none(predicate) }
    }

    /** Forgets the records seen so far. */
    fun clear() = records.clear()

    override fun start() = container.start()

    override fun stop() = container.stop()

    override fun isRunning(): Boolean = container.isRunning
}

/**
 * Declares a [RecordedEvents] bean that records the six topics and their dead-letter topics of the broker the
 * application talks to. Import it with [KafkaTestConfig] and inject `RecordedEvents` into the test.
 */
@TestConfiguration(proxyBeanMethods = false)
class RecordedEventsConfig {
    @Bean
    fun recordedEvents(connection: KafkaConnectionDetails): RecordedEvents =
        RecordedEvents(connection.bootstrapServers, Topic.ALL + Topic.ALL.map(Topic::deadLetter))
}
