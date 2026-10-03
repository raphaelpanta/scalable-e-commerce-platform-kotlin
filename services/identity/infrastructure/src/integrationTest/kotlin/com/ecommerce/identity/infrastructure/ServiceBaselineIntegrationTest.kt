package com.ecommerce.identity.infrastructure

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.json.JsonCompareMode
import java.time.Duration

private val TIMEOUT: Duration = Duration.ofSeconds(10)

class ServiceBaselineIntegrationTest : IdentityIntegrationTest() {
    @Test
    fun healthIsUp() {
        client
            .get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .json("""{"status":"UP"}""", JsonCompareMode.STRICT)
    }

    /** The versioned migrations; the repeatable seed script of the `seed` profile has no version. */
    @Test
    fun migrationsAreRecordedInOrder() {
        val versions =
            database
                .sql(
                    "SELECT version FROM flyway_schema_history " +
                        "WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                ).map { row -> checkNotNull(row.get("version", String::class.java)) }
                .all()
                .collectList()
                .block(TIMEOUT)

        versions shouldBe listOf("1", "1.1", "2", "3")
    }
}
