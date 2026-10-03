package com.ecommerce.catalog.infrastructure

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

private val TIMEOUT: Duration = Duration.ofSeconds(10)

/** Flyway applied the baseline, the messaging tables and every catalogue migration at start-up. */
class FlywayBaselineIntegrationTest : CatalogIntegrationTest() {
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

    @Test
    fun catalogMigrationsAreAppliedWithoutTheSeed() {
        val versions =
            database
                .sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .map { row -> row.get("version", String::class.java).orEmpty() }
                .all()
                .collectList()
                .block(TIMEOUT)

        versions shouldBe listOf("1", "1.1", "2", "3", "4", "5", "6")
    }
}
