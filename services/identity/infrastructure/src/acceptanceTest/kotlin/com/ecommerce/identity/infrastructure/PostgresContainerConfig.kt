package com.ecommerce.identity.infrastructure

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * A throwaway PostgreSQL with ephemeral credentials, started by Spring Boot's Testcontainers lifecycle.
 * `@ServiceConnection` points both R2DBC (through `testcontainers-r2dbc`) and Flyway (JDBC) at it.
 */
@TestConfiguration(proxyBeanMethods = false)
internal class PostgresContainerConfig {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer("postgres:18-alpine")
}
