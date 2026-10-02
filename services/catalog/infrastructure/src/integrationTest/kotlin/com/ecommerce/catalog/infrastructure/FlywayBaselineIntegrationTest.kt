package com.ecommerce.catalog.infrastructure

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Duration

private val TIMEOUT: Duration = Duration.ofSeconds(10)

@SpringBootTest
@Import(PostgresContainerConfig::class)
class FlywayBaselineIntegrationTest(
    @Autowired private val database: DatabaseClient,
) {
    @Test
    fun baselineMigrationIsRecordedAsVersionOneAndSucceeded() {
        val baseline =
            database
                .sql("SELECT version, description, success FROM flyway_schema_history WHERE version = '1'")
                .fetch()
                .one()
                .block(TIMEOUT)

        baseline shouldBe mapOf("version" to "1", "description" to "baseline", "success" to true)
    }

    @Test
    fun schemaMarkerTableExists() {
        val tables =
            database
                .sql("SELECT count(*) AS tables FROM information_schema.tables WHERE table_name = 'schema_marker'")
                .fetch()
                .one()
                .block(TIMEOUT)

        tables shouldBe mapOf("tables" to 1L)
    }
}
