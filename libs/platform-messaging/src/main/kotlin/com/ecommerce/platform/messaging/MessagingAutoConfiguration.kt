package com.ecommerce.platform.messaging

import com.ecommerce.platform.messaging.consumer.EventListenerSupport
import com.ecommerce.platform.messaging.consumer.IdempotentConsumer
import com.ecommerce.platform.messaging.consumer.ProcessedEventPurge
import com.ecommerce.platform.messaging.envelope.EnvelopeFactory
import com.ecommerce.platform.messaging.envelope.MalformedEnvelopeException
import com.ecommerce.platform.messaging.envelope.Topic
import com.ecommerce.platform.messaging.outbox.OutboxPublisher
import com.ecommerce.platform.messaging.outbox.OutboxPurge
import com.ecommerce.platform.messaging.outbox.OutboxRelay
import com.ecommerce.platform.messaging.outbox.R2dbcOutboxStore
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.TopicPartition
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import java.time.Clock

private fun applicationName(environment: Environment): String =
    checkNotNull(environment.getProperty("spring.application.name")) {
        "spring.application.name (or platform.messaging.producer and platform.messaging.consumer.group-id) must be set"
    }

private fun clockOf(clock: ObjectProvider<Clock>): Clock = clock.getIfAvailable { Clock.systemUTC() }

/**
 * Wires the messaging library into a service (docs/service-conventions.md §5): the envelope factory and the
 * topic declarations here, the outbox store and relay in [Outbox], the idempotent consumer, its purge and the
 * Kafka listener container factory (`MANUAL_IMMEDIATE` acks, bounded exponential retry, dead-letter topic
 * `<topic>.dlt`) in [Consumer]. Runs before Spring Boot's Kafka auto-configuration so that its
 * `kafkaListenerContainerFactory` replaces Boot's. `platform.messaging.enabled=false` turns everything off.
 */
@AutoConfiguration(before = [KafkaAutoConfiguration::class])
@ConditionalOnBooleanProperty(name = ["platform.messaging.enabled"], matchIfMissing = true)
@EnableConfigurationProperties(MessagingProperties::class)
@EnableKafka
class MessagingAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun envelopeFactory(
        properties: MessagingProperties,
        environment: Environment,
        clock: ObjectProvider<Clock>,
    ): EnvelopeFactory = EnvelopeFactory(properties.producer ?: applicationName(environment), clockOf(clock))

    /** Declares the six topics of events.yaml and their dead-letter topics; existing topics are left as they are. */
    @Bean
    @ConditionalOnBooleanProperty(name = ["platform.messaging.topics.create"], matchIfMissing = true)
    fun messagingTopics(properties: MessagingProperties): KafkaAdmin.NewTopics {
        val replicas = properties.topics.replicas.toInt()

        fun topic(
            base: String,
            name: String = base,
        ): NewTopic =
            TopicBuilder
                .name(name)
                .partitions(Topic.PARTITIONS.getValue(base))
                .replicas(replicas)
                .build()

        fun deadLetter(base: String): NewTopic = topic(base, Topic.deadLetter(base))

        return KafkaAdmin.NewTopics(
            topic(Topic.ACCOUNT),
            deadLetter(Topic.ACCOUNT),
            topic(Topic.CART),
            deadLetter(Topic.CART),
            topic(Topic.STOCK),
            deadLetter(Topic.STOCK),
            topic(Topic.ORDER),
            deadLetter(Topic.ORDER),
            topic(Topic.PAYMENT),
            deadLetter(Topic.PAYMENT),
            topic(Topic.NOTIFICATION),
            deadLetter(Topic.NOTIFICATION),
        )
    }

    /** Producer side: the transactional outbox, its relay to Kafka and the purge of published rows. */
    @Configuration(proxyBeanMethods = false)
    class Outbox {
        @Bean
        @ConditionalOnMissingBean(OutboxPublisher::class)
        fun outboxPublisher(database: DatabaseClient): OutboxPublisher = R2dbcOutboxStore(database)

        @Bean
        @ConditionalOnMissingBean
        fun outboxRelay(
            database: DatabaseClient,
            transactionManager: ReactiveTransactionManager,
            kafkaTemplate: KafkaTemplate<String, String>,
            properties: MessagingProperties,
            meters: ObjectProvider<MeterRegistry>,
        ): OutboxRelay =
            OutboxRelay(
                database = database,
                transactions = TransactionalOperator.create(transactionManager),
                kafka = kafkaTemplate,
                settings = OutboxRelay.Settings(properties.outbox.batchSize, properties.outbox.sendTimeout),
                meters = meters.getIfAvailable { Metrics.globalRegistry },
            )

        @Bean
        @ConditionalOnBooleanProperty(name = ["platform.messaging.outbox.relay-enabled"], matchIfMissing = true)
        fun outboxRelayJob(
            relay: OutboxRelay,
            properties: MessagingProperties,
        ): PeriodicJob =
            PeriodicJob("outbox-relay", properties.outbox.pollInterval) {
                relay.relayBatch() >= properties.outbox.batchSize
            }

        @Bean
        @ConditionalOnMissingBean
        fun outboxPurge(
            database: DatabaseClient,
            properties: MessagingProperties,
            clock: ObjectProvider<Clock>,
        ): OutboxPurge = OutboxPurge(database, properties.outbox.retention, clockOf(clock))
    }

    /** Consumer side: idempotent handling, the hourly purge and the Kafka listener plumbing. */
    @Configuration(proxyBeanMethods = false)
    class Consumer {
        @Bean
        @ConditionalOnMissingBean
        fun idempotentConsumer(
            database: DatabaseClient,
            transactionManager: ReactiveTransactionManager,
            clock: ObjectProvider<Clock>,
        ): IdempotentConsumer =
            IdempotentConsumer(database, TransactionalOperator.create(transactionManager), clockOf(clock))

        @Bean
        @ConditionalOnMissingBean
        fun eventListenerSupport(
            idempotentConsumer: IdempotentConsumer,
            properties: MessagingProperties,
            environment: Environment,
        ): EventListenerSupport =
            EventListenerSupport(idempotentConsumer, properties.consumer.groupId ?: applicationName(environment))

        @Bean
        @ConditionalOnMissingBean
        fun processedEventPurge(
            database: DatabaseClient,
            properties: MessagingProperties,
            clock: ObjectProvider<Clock>,
        ): ProcessedEventPurge = ProcessedEventPurge(database, properties.processedEvents.retention, clockOf(clock))

        /** Hourly by default: processed-event markers and published outbox rows past their retention. */
        @Bean
        fun messagingPurgeJob(
            processedEvents: ProcessedEventPurge,
            outbox: OutboxPurge,
            properties: MessagingProperties,
        ): PeriodicJob =
            PeriodicJob("messaging-purge", properties.processedEvents.purgeInterval) {
                processedEvents.purge()
                outbox.purge()
                false
            }

        /** Retries a failing record with exponential backoff, then publishes it to `<topic>.dlt` and commits it. */
        @Bean
        @ConditionalOnMissingBean(name = ["messagingErrorHandler"])
        fun messagingErrorHandler(
            kafkaTemplate: KafkaTemplate<String, String>,
            properties: MessagingProperties,
        ): DefaultErrorHandler {
            val retry = properties.consumer
            require(retry.maxAttempts >= 1) { "platform.messaging.consumer.max-attempts must be at least 1" }
            val recoverer =
                DeadLetterPublishingRecoverer(kafkaTemplate) { record, _ ->
                    // A negative partition lets the producer partition the dead letter by its key.
                    TopicPartition(record.topic() + Topic.DEAD_LETTER_SUFFIX, -1)
                }
            val backOff =
                ExponentialBackOffWithMaxRetries(retry.maxAttempts - 1).apply {
                    initialInterval = retry.initialInterval.toMillis()
                    multiplier = retry.multiplier
                    maxInterval = retry.maxInterval.toMillis()
                }
            return DefaultErrorHandler(recoverer, backOff).apply {
                // Committing a recovered record requires MANUAL_IMMEDIATE, the ack mode of the factory below.
                setCommitRecovered(true)
                addNotRetryableExceptions(MalformedEnvelopeException::class.java)
            }
        }

        @Bean(name = ["kafkaListenerContainerFactory"])
        @ConditionalOnMissingBean(name = ["kafkaListenerContainerFactory"])
        fun kafkaListenerContainerFactory(
            consumerFactory: ConsumerFactory<String, String>,
            @Qualifier("messagingErrorHandler") errorHandler: DefaultErrorHandler,
        ): ConcurrentKafkaListenerContainerFactory<String, String> =
            ConcurrentKafkaListenerContainerFactory<String, String>().apply {
                setConsumerFactory(consumerFactory)
                setCommonErrorHandler(errorHandler)
                containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
                containerProperties.isObservationEnabled = true
            }
    }
}
