package com.ecommerce.platform.testing

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * A throwaway PostgreSQL ([Images.POSTGRES]) with ephemeral credentials, started by Spring Boot's Testcontainers
 * lifecycle; `@ServiceConnection` points R2DBC (with `testcontainers-r2dbc`) and Flyway (JDBC) at it.
 * Use with `@Import(PostgresTestConfig::class)`.
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfig {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer(Images.POSTGRES)
}

/**
 * A single-node Kafka in KRaft mode ([Images.KAFKA]); `@ServiceConnection` sets `spring.kafka.bootstrap-servers`.
 * Use with `@Import(KafkaTestConfig::class)`.
 */
@TestConfiguration(proxyBeanMethods = false)
class KafkaTestConfig {
    @Bean
    @ServiceConnection
    fun kafka(): KafkaContainer = KafkaContainer(Images.KAFKA)
}
