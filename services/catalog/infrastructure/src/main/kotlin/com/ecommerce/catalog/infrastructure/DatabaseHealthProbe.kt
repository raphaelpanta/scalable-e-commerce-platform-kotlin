package com.ecommerce.catalog.infrastructure

import com.ecommerce.catalog.application.HealthProbe
import com.ecommerce.catalog.domain.HealthStatus
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.seconds

/** Outbound adapter: the database is up when it answers `SELECT 1` within two seconds. */
@Component
class DatabaseHealthProbe(
    private val database: DatabaseClient,
) : HealthProbe {
    override suspend fun check(): HealthStatus {
        val answered =
            try {
                withTimeoutOrNull(TIMEOUT) {
                    database.sql("SELECT 1").fetch().awaitOneOrNull()
                    true
                } ?: false
            } catch (failure: DataAccessException) {
                log.warn("database probe failed: {}", failure.message)
                false
            }
        return if (answered) HealthStatus.Up else HealthStatus.down(UNREACHABLE)
    }

    private companion object {
        val TIMEOUT = 2.seconds
        const val UNREACHABLE = "database unreachable"
        val log: Logger = LoggerFactory.getLogger(DatabaseHealthProbe::class.java)
    }
}
