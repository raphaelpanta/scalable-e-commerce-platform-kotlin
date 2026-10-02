package com.ecommerce.platform.messaging.testing

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.kafka.KafkaContainer

/**
 * Single-node KRaft broker image of the tests (the same `apache/kafka` line as the Compose `core` profile).
 * Mirrors `com.ecommerce.platform.testing.Images.KAFKA` of platform-core's test fixtures.
 */
const val KAFKA_IMAGE = "apache/kafka:4.1.1"

/**
 * A throwaway Kafka broker started by Spring Boot's Testcontainers lifecycle; `@ServiceConnection` points
 * `spring.kafka.bootstrap-servers` (producer, consumers, admin) at it. Import it next to the service's PostgreSQL
 * container configuration: `@Import(PostgresContainerConfig::class, KafkaTestConfig::class)`.
 */
@TestConfiguration(proxyBeanMethods = false)
class KafkaTestConfig {
    @Bean
    @ServiceConnection
    fun kafka(): KafkaContainer = KafkaContainer(KAFKA_IMAGE)
}
